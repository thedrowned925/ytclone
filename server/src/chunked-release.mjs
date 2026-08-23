import { Readable } from 'node:stream'

const CACHE_TTL_MS = 5 * 60 * 1000
const releaseCache = new Map()
const manifestCache = new Map()

function githubHeaders(token, accept = 'application/vnd.github+json') {
  return {
    Accept: accept,
    Authorization: `Bearer ${token}`,
    'X-GitHub-Api-Version': '2022-11-28',
    'User-Agent': 'ytclone-server',
  }
}

export function parseByteRange(header, totalSize) {
  if (!header) return { start: 0, end: totalSize - 1, partial: false }
  const match = /^bytes=(\d*)-(\d*)$/i.exec(String(header).trim())
  if (!match) throw Object.assign(new Error('Invalid Range header'), { statusCode: 416 })

  let start
  let end
  if (match[1] === '' && match[2] !== '') {
    const suffix = Number(match[2])
    if (!Number.isFinite(suffix) || suffix <= 0) throw Object.assign(new Error('Invalid suffix range'), { statusCode: 416 })
    start = Math.max(0, totalSize - suffix)
    end = totalSize - 1
  } else {
    start = Number(match[1])
    end = match[2] === '' ? totalSize - 1 : Number(match[2])
  }

  if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start < 0 || end < start || start >= totalSize) {
    throw Object.assign(new Error('Range not satisfiable'), { statusCode: 416 })
  }
  end = Math.min(end, totalSize - 1)
  return { start, end, partial: true }
}

export function mapLogicalRange(file, start, end) {
  const slices = []
  for (const part of file.parts || []) {
    const partStart = Number(part.offset)
    const partEnd = partStart + Number(part.sizeBytes) - 1
    if (end < partStart || start > partEnd) continue

    const logicalStart = Math.max(start, partStart)
    const logicalEnd = Math.min(end, partEnd)
    slices.push({
      assetName: part.name,
      logicalStart,
      logicalEnd,
      assetStart: logicalStart - partStart,
      assetEnd: logicalEnd - partStart,
      length: logicalEnd - logicalStart + 1,
    })
  }

  const expected = end - start + 1
  const mapped = slices.reduce((sum, slice) => sum + slice.length, 0)
  if (mapped !== expected) {
    throw new Error(`Storage manifest is incomplete: mapped ${mapped}/${expected} bytes`)
  }
  return slices
}

export function findLogicalFile(storageManifest, logicalName) {
  const file = (storageManifest.files || []).find((item) => item.logicalName === logicalName)
  if (!file) throw Object.assign(new Error(`Logical file not found: ${logicalName}`), { statusCode: 404 })
  return file
}

async function writeStreamSlice(response, output, { skip = 0, take }) {
  if (!response.body) throw new Error('GitHub asset response has no body')
  let remainingSkip = skip
  let remainingTake = take

  for await (const chunkValue of Readable.fromWeb(response.body)) {
    if (remainingTake <= 0) break
    let chunk = chunkValue

    if (remainingSkip >= chunk.length) {
      remainingSkip -= chunk.length
      continue
    }
    if (remainingSkip > 0) {
      chunk = chunk.subarray(remainingSkip)
      remainingSkip = 0
    }
    if (chunk.length > remainingTake) chunk = chunk.subarray(0, remainingTake)

    if (!output.write(chunk)) await new Promise((resolve) => output.once('drain', resolve))
    remainingTake -= chunk.length
  }

  if (remainingTake !== 0) throw new Error(`Upstream asset ended early; ${remainingTake} bytes missing`)
}

async function listReleaseAssets({ owner, repo, releaseId, token }) {
  const assets = []
  for (let page = 1; page <= 10; page += 1) {
    const response = await fetch(
      `https://api.github.com/repos/${owner}/${repo}/releases/${releaseId}/assets?per_page=100&page=${page}`,
      { headers: githubHeaders(token) },
    )
    if (!response.ok) throw Object.assign(new Error(`GitHub asset list failed: ${response.status}`), { statusCode: response.status })
    const batch = await response.json()
    assets.push(...batch)
    if (batch.length < 100) break
  }
  return assets
}

export async function fetchReleaseByTag({ owner, repo, tag, token }) {
  const cacheKey = `${owner}/${repo}:${tag}`
  const cached = releaseCache.get(cacheKey)
  if (cached && Date.now() - cached.at < CACHE_TTL_MS) return cached.value

  const response = await fetch(`https://api.github.com/repos/${owner}/${repo}/releases/tags/${encodeURIComponent(tag)}`, {
    headers: githubHeaders(token),
  })
  if (!response.ok) throw Object.assign(new Error(`GitHub release lookup failed: ${response.status}`), { statusCode: response.status })
  const release = await response.json()
  release.assets = await listReleaseAssets({ owner, repo, releaseId: release.id, token })
  releaseCache.set(cacheKey, { at: Date.now(), value: release })
  return release
}

export async function fetchAssetResponse({ owner, repo, assetId, token, start = null, end = null }) {
  const requestHeaders = githubHeaders(token, 'application/octet-stream')
  if (start !== null && end !== null) requestHeaders.Range = `bytes=${start}-${end}`

  const response = await fetch(`https://api.github.com/repos/${owner}/${repo}/releases/assets/${assetId}`, {
    headers: requestHeaders,
    redirect: 'follow',
  })
  if (!response.ok && response.status !== 206) {
    throw Object.assign(new Error(`GitHub asset download failed: ${response.status}`), { statusCode: response.status })
  }
  return response
}

export async function loadStorageManifest({ owner, repo, release, token }) {
  const cacheKey = `${owner}/${repo}:${release.id}:storage-manifest`
  const cached = manifestCache.get(cacheKey)
  if (cached && Date.now() - cached.at < CACHE_TTL_MS) return cached.value

  const asset = (release.assets || []).find((item) => item.name === 'storage-manifest.json')
  if (!asset) throw new Error('storage-manifest.json asset is missing')
  const response = await fetchAssetResponse({ owner, repo, assetId: asset.id, token })
  const manifest = await response.json()
  manifestCache.set(cacheKey, { at: Date.now(), value: manifest })
  return manifest
}

export async function streamLogicalFile({ req, res, owner, repo, release, storageManifest, logicalName, token, contentType = 'application/octet-stream' }) {
  const file = findLogicalFile(storageManifest, logicalName)
  const totalSize = Number(file.sizeBytes)
  const range = parseByteRange(req.headers.range, totalSize)
  const slices = mapLogicalRange(file, range.start, range.end)
  const assetMap = new Map((release.assets || []).map((asset) => [asset.name, asset]))
  const contentLength = range.end - range.start + 1

  res.statusCode = range.partial ? 206 : 200
  res.setHeader('Accept-Ranges', 'bytes')
  res.setHeader('Content-Type', contentType)
  res.setHeader('Content-Length', String(contentLength))
  res.setHeader('Cache-Control', 'private, max-age=3600')
  if (range.partial) res.setHeader('Content-Range', `bytes ${range.start}-${range.end}/${totalSize}`)

  for (const slice of slices) {
    const asset = assetMap.get(slice.assetName)
    if (!asset) throw new Error(`Release asset missing: ${slice.assetName}`)
    const upstream = await fetchAssetResponse({
      owner,
      repo,
      assetId: asset.id,
      token,
      start: slice.assetStart,
      end: slice.assetEnd,
    })

    // GitHub/object storage normally honors Range with 206. If an upstream
    // ever answers 200, skip bytes locally as a correctness fallback.
    const honoredRange = upstream.status === 206
    await writeStreamSlice(upstream, res, {
      skip: honoredRange ? 0 : slice.assetStart,
      take: slice.length,
    })
  }

  res.end()
}
