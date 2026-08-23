import http from 'node:http'
import path from 'node:path'
import {
  fetchAssetResponse,
  fetchReleaseByTag,
  loadStorageManifest,
  streamLogicalFile,
} from './chunked-release.mjs'

const PORT = Number(process.env.PORT || 8787)
const GITHUB_TOKEN = process.env.YTCLONE_GITHUB_TOKEN || ''
const MEDIA_REPO = process.env.YTCLONE_MEDIA_REPO || ''
const APP_TOKEN = process.env.YTCLONE_APP_TOKEN || ''
const QUALITY_ORDER = ['1080p', '720p', '480p', '360p']

function parseRepo(value) {
  const [owner, repo, ...rest] = String(value).split('/')
  if (!owner || !repo || rest.length) throw new Error('YTCLONE_MEDIA_REPO owner/repo biçiminde olmalı.')
  return { owner, repo }
}

function sendJson(res, status, body) {
  const data = Buffer.from(JSON.stringify(body))
  res.statusCode = status
  res.setHeader('Content-Type', 'application/json; charset=utf-8')
  res.setHeader('Content-Length', String(data.length))
  res.end(data)
}

function authorized(req) {
  if (!APP_TOKEN) return true
  return req.headers.authorization === `Bearer ${APP_TOKEN}`
}

function contentTypeFor(name) {
  const ext = path.extname(name).toLowerCase()
  return ({
    '.mp4': 'video/mp4',
    '.m4v': 'video/mp4',
    '.mkv': 'video/x-matroska',
    '.webm': 'video/webm',
    '.m4a': 'audio/mp4',
    '.aac': 'audio/aac',
    '.mp3': 'audio/mpeg',
    '.opus': 'audio/ogg',
    '.vtt': 'text/vtt; charset=utf-8',
    '.json': 'application/json; charset=utf-8',
  })[ext] || 'application/octet-stream'
}

async function readJsonReleaseAsset({ owner, repo, release, token, name }) {
  const asset = (release.assets || []).find((item) => item.name === name)
  if (!asset) throw Object.assign(new Error(`${name} asset bulunamadı`), { statusCode: 404 })
  const response = await fetchAssetResponse({ owner, repo, assetId: asset.id, token })
  return response.json()
}

function chooseQuality(manifest, requested) {
  if (requested === 'original' && manifest.original?.file) return manifest.original.file
  if (requested && requested !== 'auto' && manifest.qualities?.[requested]?.file) return manifest.qualities[requested].file
  for (const quality of QUALITY_ORDER) {
    if (manifest.qualities?.[quality]?.file) return manifest.qualities[quality].file
  }
  if (manifest.original?.file) return manifest.original.file
  throw Object.assign(new Error('Oynatılabilir video sürümü bulunamadı.'), { statusCode: 404 })
}

async function getVideoContext(videoId) {
  const { owner, repo } = parseRepo(MEDIA_REPO)
  const tag = `ytclone-${videoId}`
  const release = await fetchReleaseByTag({ owner, repo, tag, token: GITHUB_TOKEN })
  const [storageManifest, manifest] = await Promise.all([
    loadStorageManifest({ owner, repo, release, token: GITHUB_TOKEN }),
    readJsonReleaseAsset({ owner, repo, release, token: GITHUB_TOKEN, name: 'manifest.json' }),
  ])
  return { owner, repo, release, storageManifest, manifest }
}

async function handleVideoRoute(req, res, url) {
  const match = /^\/api\/videos\/([^/]+)\/(play|download)(?:\/)?$/.exec(url.pathname)
  if (!match) return false

  const videoId = decodeURIComponent(match[1])
  const operation = match[2]
  const context = await getVideoContext(videoId)
  const requestedQuality = url.searchParams.get('quality') || (operation === 'download' ? 'original' : 'auto')
  const logicalName = chooseQuality(context.manifest, requestedQuality)

  res.setHeader('Content-Disposition', operation === 'download'
    ? `attachment; filename*=UTF-8''${encodeURIComponent(logicalName)}`
    : 'inline')

  await streamLogicalFile({
    req,
    res,
    ...context,
    logicalName,
    token: GITHUB_TOKEN,
    contentType: contentTypeFor(logicalName),
  })
  return true
}

async function handleAudioRoute(req, res, url) {
  const match = /^\/api\/videos\/([^/]+)\/audio\/([^/]+)(?:\/)?$/.exec(url.pathname)
  if (!match) return false

  const videoId = decodeURIComponent(match[1])
  const trackId = decodeURIComponent(match[2])
  const context = await getVideoContext(videoId)
  const track = (context.manifest.audioTracks || []).find((item) => item.id === trackId)
  if (!track?.file) throw Object.assign(new Error('Ses parçası bulunamadı.'), { statusCode: 404 })

  await streamLogicalFile({
    req,
    res,
    ...context,
    logicalName: track.file,
    token: GITHUB_TOKEN,
    contentType: contentTypeFor(track.file),
  })
  return true
}

const server = http.createServer(async (req, res) => {
  try {
    if (!GITHUB_TOKEN || !MEDIA_REPO) {
      return sendJson(res, 503, { error: 'YTClone server henüz GitHub media repository ile yapılandırılmadı.' })
    }
    if (!authorized(req)) return sendJson(res, 401, { error: 'Unauthorized' })

    const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`)
    if (url.pathname === '/health') return sendJson(res, 200, { ok: true })
    if (await handleVideoRoute(req, res, url)) return
    if (await handleAudioRoute(req, res, url)) return

    return sendJson(res, 404, { error: 'Not found' })
  } catch (error) {
    if (res.headersSent) {
      res.destroy(error)
      return
    }
    const status = Number(error.statusCode || 500)
    if (status === 416) res.setHeader('Content-Range', 'bytes */*')
    sendJson(res, status, { error: error.message })
  }
})

server.listen(PORT, () => {
  console.log(`YTClone trusted API listening on :${PORT}`)
})
