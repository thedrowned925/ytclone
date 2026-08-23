import fs from 'node:fs'
import fsp from 'node:fs/promises'
import path from 'node:path'

const GITHUB_API = 'https://api.github.com'
const MAX_ASSET_BYTES = 2 * 1024 * 1024 * 1024

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
  const releases = await githubJson(token, `${GITHUB_API}/repos/${owner}/${repo}/releases?per_page=100`)
  const existing = releases.find((release) => release.tag_name === tag)
  if (existing) return existing

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

async function deleteAsset({ token, owner, repo, assetId }) {
  await githubJson(token, `${GITHUB_API}/repos/${owner}/${repo}/releases/assets/${assetId}`, {
    method: 'DELETE',
  })
}

async function uploadAsset({ token, owner, repo, releaseId, filePath, name }) {
  const stat = await fsp.stat(filePath)
  if (stat.size >= MAX_ASSET_BYTES) {
    throw new Error(`${name} GitHub Release asset sınırını aşıyor (${stat.size} byte). Dosya yerelde korundu; yayın durduruldu.`)
  }

  const response = await fetch(
    `https://uploads.github.com/repos/${owner}/${repo}/releases/${releaseId}/assets?name=${encodeURIComponent(name)}`,
    {
      method: 'POST',
      headers: headers(token, {
        'Content-Type': 'application/octet-stream',
        'Content-Length': String(stat.size),
      }),
      body: fs.createReadStream(filePath),
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

export async function publishDirectory({ outputDir, manifest, repo: repoValue, token }) {
  if (!token) throw new Error('YTCLONE_GITHUB_TOKEN ayarlı değil.')
  const { owner, repo } = parseRepo(repoValue)
  const tag = `ytclone-${manifest.id}`
  let release = await getOrCreateRelease({ token, owner, repo, tag, title: manifest.title })

  const names = (await fsp.readdir(outputDir)).sort()
  const files = []
  for (const name of names) {
    const filePath = path.join(outputDir, name)
    const stat = await fsp.stat(filePath)
    if (stat.isFile()) files.push({ name, filePath, size: stat.size })
  }

  for (const file of files) {
    const existing = (release.assets || []).find((asset) => asset.name === file.name)
    if (existing && Number(existing.size) === file.size) {
      console.log(`✓ zaten yüklü: ${file.name}`)
      continue
    }
    if (existing) {
      console.log(`↻ değişen asset yeniden yükleniyor: ${file.name}`)
      await deleteAsset({ token, owner, repo, assetId: existing.id })
    }
    console.log(`↑ ${file.name}`)
    await uploadAsset({ token, owner, repo, releaseId: release.id, filePath: file.filePath, name: file.name })
    release = await githubJson(token, `${GITHUB_API}/repos/${owner}/${repo}/releases/${release.id}`)
  }

  const published = await publishRelease({ token, owner, repo, releaseId: release.id, title: manifest.title })
  return {
    id: published.id,
    tag: published.tag_name,
    url: published.html_url,
    assets: published.assets || [],
  }
}
