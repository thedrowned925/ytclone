import fs from 'node:fs'
import fsp from 'node:fs/promises'
import path from 'node:path'

const GITHUB_API = 'https://api.github.com'
const GITHUB_ASSET_LIMIT_BYTES = 2 * 1024 * 1024 * 1024
const GITHUB_RELEASE_ASSET_LIMIT = 1000
const RESERVED_RELEASE_ASSETS = 10
// Stay comfortably below GitHub's 2 GiB per-asset limit.
export const CHUNK_SIZE_BYTES = Math.floor(1.8 * 1024 * 1024 * 1024)

function headers(token, extra = {}) {
  return {
    Accept: 'application/vnd.github+json',
    Authorization: `Bearer ${token}`,
    'X-GitHub-Api-Version': '2022-11-28',
    'User-Agent': 'ytclone-uploader',
    ...extra,
  }
}

async function githubJson(token, url, options = {}) {
  const response = await fetch(url, {
    ...options,
    headers: headers(token, options.headers || {}),
  })
  if (!response.ok) {
    const text = await response.text()
    throw new Error(`GitHub ${response.status}: ${text}`)
  }
  if (response.status === 204) return null
  return response.json()
}

async function getOrCreateRelease({ token, owner, repo, tag, title }) {
  // Draft releases are included for authenticated users. Paginate so an older
  // unfinished upload can still be resumed even when the repository has many releases.
  for (let page = 1; page <= 100; page += 1) {
    const releases = await githubJson(token, `${GITHUB_API}/repos/${owner}/${repo}/releases?per_page=100&page=${page}`)
    const existing = releases.find((release) => release.tag_name === tag)
    if (existing) return existing
    if (releases.length < 100) break
  }

  return githubJson(token, `${GITHUB_API}/repos/${owner}/${repo}/releases`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      tag_name: tag,
      name: title,
      body: 'Published by YTClone Uploader.',
      draft: true,
      prerelease: false,
    }),
  })
}

async function listReleaseAssets({ token, owner, repo, releaseId }) {
  const assets = []
  for (let page = 1; page <= 10; page += 1) {
    const batch = await githubJson(
      token,
      `${GITHUB_API}/repos/${owner}/${repo}/releases/${releaseId}/assets?per_page=100&page=${page}`,
    )
    assets.push(...batch)
    if (batch.length < 100) break
  }
  return assets
}

async function deleteAsset({ token, owner, repo, assetId }) {
  await githubJson(token, `${GITHUB_API}/repos/${owner}/${repo}/releases/assets/${assetId}`, {
    method: 'DELETE',
  })
}

async function uploadAssetStream({ token, owner, repo, releaseId, filePath, name, start = 0, length }) {
  if (length >= GITHUB_ASSET_LIMIT_BYTES) {
    throw new Error(`${name} güvenli GitHub asset boyutunu aşıyor (${length} byte).`)
  }

  const end = start + length - 1
  const response = await fetch(
    `https://uploads.github.com/repos/${owner}/${repo}/releases/${releaseId}/assets?name=${encodeURIComponent(name)}`,
    {
      method: 'POST',
      headers: headers(token, {
        'Content-Type': 'application/octet-stream',
        'Content-Length': String(length),
      }),
      body: fs.createReadStream(filePath, { start, end }),
      duplex: 'half',
    },
  )

  if (!response.ok) {
    const text = await response.text()
    throw new Error(`Asset yüklenemedi (${name}) GitHub ${response.status}: ${text}`)
  }
  return response.json()
}

async function publishRelease({ token, owner, repo, releaseId, title }) {
  return githubJson(token, `${GITHUB_API}/repos/${owner}/${repo}/releases/${releaseId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name: title, draft: false }),
  })
}

export function parseRepo(value) {
  const [owner, repo, ...rest] = String(value || '').split('/')
  if (!owner || !repo || rest.length) throw new Error('Media repo owner/repo biçiminde olmalı.')
  return { owner, repo }
}

function partName(fileName, partIndex, totalParts) {
  if (totalParts === 1) return fileName
  return `${fileName}.part${String(partIndex + 1).padStart(4, '0')}`
}

function planFile(file) {
  const totalParts = Math.max(1, Math.ceil(file.size / CHUNK_SIZE_BYTES))
  const parts = []

  for (let index = 0; index < totalParts; index += 1) {
    const start = index * CHUNK_SIZE_BYTES
    const length = Math.min(CHUNK_SIZE_BYTES, file.size - start)
    parts.push({
      index,
      name: partName(file.name, index, totalParts),
      start,
      length,
    })
  }

  return {
    logicalName: file.name,
    sizeBytes: file.size,
    chunked: totalParts > 1,
    chunkSizeBytes: totalParts > 1 ? CHUNK_SIZE_BYTES : null,
    parts,
  }
}

async function uploadPlannedPart({ token, owner, repo, releaseId, assetMap, file, part }) {
  const existing = assetMap.get(part.name)
  if (existing && Number(existing.size) === part.length) {
    console.log(`✓ zaten yüklü: ${part.name}`)
    return
  }

  if (existing) {
    console.log(`↻ değişen asset yeniden yükleniyor: ${part.name}`)
    await deleteAsset({ token, owner, repo, assetId: existing.id })
    assetMap.delete(part.name)
  }

  console.log(`↑ ${part.name} (${(part.length / 1024 / 1024).toFixed(1)} MiB)`)
  const uploaded = await uploadAssetStream({
    token,
    owner,
    repo,
    releaseId,
    filePath: file.filePath,
    name: part.name,
    start: part.start,
    length: part.length,
  })
  assetMap.set(uploaded.name, uploaded)
}

export async function publishDirectory({ outputDir, manifest, repo: repoValue, token }) {
  if (!token) throw new Error('YTCLONE_GITHUB_TOKEN ayarlı değil.')
  const { owner, repo } = parseRepo(repoValue)
  const tag = `ytclone-${manifest.id}`
  const release = await getOrCreateRelease({ token, owner, repo, tag, title: manifest.title })
  const existingAssets = await listReleaseAssets({ token, owner, repo, releaseId: release.id })
  const assetMap = new Map(existingAssets.map((asset) => [asset.name, asset]))

  const names = (await fsp.readdir(outputDir)).sort()
  const files = []
  for (const name of names) {
    // The storage map is generated by this publisher and uploaded last.
    if (name === 'storage-manifest.json' || name === 'publish.json') continue
    const filePath = path.join(outputDir, name)
    const stat = await fsp.stat(filePath)
    if (stat.isFile()) files.push({ name, filePath, size: stat.size })
  }

  const plans = files.map((file) => ({ file, plan: planFile(file) }))
  const plannedAssetCount = plans.reduce((sum, entry) => sum + entry.plan.parts.length, 0) + 1 // storage-manifest.json
  const safeAssetCeiling = GITHUB_RELEASE_ASSET_LIMIT - RESERVED_RELEASE_ASSETS
  if (plannedAssetCount > safeAssetCeiling) {
    throw new Error(
      `Bu video ${plannedAssetCount} Release asset gerektiriyor; güvenli tek-Release sınırı ${safeAssetCeiling}. `
      + 'Bir sonraki storage sürümünde video birden fazla Release shardına dağıtılmalı.',
    )
  }

  const storageFiles = []
  for (const { file, plan } of plans) {
    if (plan.chunked) {
      console.log(`↳ ${file.name}: ${plan.parts.length} parça × en fazla ${(CHUNK_SIZE_BYTES / 1024 / 1024 / 1024).toFixed(1)} GiB`)
    }

    for (const part of plan.parts) {
      await uploadPlannedPart({ token, owner, repo, releaseId: release.id, assetMap, file, part })
    }

    storageFiles.push({
      logicalName: plan.logicalName,
      sizeBytes: plan.sizeBytes,
      chunked: plan.chunked,
      chunkSizeBytes: plan.chunkSizeBytes,
      parts: plan.parts.map((part) => ({
        name: part.name,
        offset: part.start,
        sizeBytes: part.length,
      })),
    })
  }

  const storageManifest = {
    schemaVersion: 1,
    videoId: manifest.id,
    releaseTag: tag,
    chunkSizeBytes: CHUNK_SIZE_BYTES,
    files: storageFiles,
  }
  const storageManifestPath = path.join(outputDir, 'storage-manifest.json')
  await fsp.writeFile(storageManifestPath, `${JSON.stringify(storageManifest, null, 2)}\n`)
  const storageStat = await fsp.stat(storageManifestPath)
  const storageFile = { name: 'storage-manifest.json', filePath: storageManifestPath, size: storageStat.size }
  const storagePlan = planFile(storageFile)
  await uploadPlannedPart({
    token,
    owner,
    repo,
    releaseId: release.id,
    assetMap,
    file: storageFile,
    part: storagePlan.parts[0],
  })

  const published = await publishRelease({ token, owner, repo, releaseId: release.id, title: manifest.title })
  return {
    id: published.id,
    tag: published.tag_name,
    url: published.html_url,
    chunkSizeBytes: CHUNK_SIZE_BYTES,
    files: storageFiles,
    assetCount: assetMap.size,
  }
}
