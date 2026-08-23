import fs from 'node:fs/promises'
import path from 'node:path'
import { createHash } from 'node:crypto'
import { run, ffprobeJson } from './commands.mjs'

const QUALITY_PRESETS = [
  { label: '1080p', height: 1080, crf: 20, audio: '192k' },
  { label: '720p', height: 720, crf: 21, audio: '160k' },
  { label: '480p', height: 480, crf: 22, audio: '128k' },
  { label: '360p', height: 360, crf: 23, audio: '112k' },
]

function isHttpUrl(value) {
  try {
    const url = new URL(value)
    return url.protocol === 'http:' || url.protocol === 'https:'
  } catch {
    return false
  }
}

function clean(value = '') {
  return String(value).replace(/[<>:"/\\|?*\u0000-\u001f]/g, ' ').replace(/\s+/g, ' ').trim()
}

function safeId(value) {
  const cleaned = clean(value).toLowerCase().replace(/[^a-z0-9._-]+/g, '-').replace(/^-+|-+$/g, '')
  return cleaned || createHash('sha1').update(String(value)).digest('hex').slice(0, 12)
}

function trackName(format = {}) {
  const note = String(format.format_note || format.format || '')
    .replace(/\b\d+(?:\.\d+)?k\b/gi, '')
    .replace(/\b(?:low|medium|high)\b/gi, '')
    .replace(/\s+/g, ' ')
    .trim()
  return clean(note.split(',')[0] || format.language || 'Audio')
}

function scoreAudio(format = {}) {
  return Number(format.preference || 0) * 1_000_000
    + Number(format.quality || 0) * 100_000
    + Number(format.abr || format.tbr || 0) * 100
    + Number(format.audio_channels || 0)
}

function bestAudioTracks(formats = []) {
  const groups = new Map()
  for (const format of formats) {
    if (!format || format.acodec === 'none' || format.vcodec !== 'none') continue
    const language = format.language || 'und'
    const name = trackName(format)
    const key = `${language}::${name}`
    const current = groups.get(key)
    if (!current || scoreAudio(format) > scoreAudio(current)) groups.set(key, format)
  }
  return [...groups.values()]
}

async function lastExistingPrintedPath(stdout) {
  const lines = stdout.split(/\r?\n/).map((line) => line.trim()).filter(Boolean).reverse()
  for (const line of lines) {
    try {
      await fs.access(line)
      return line
    } catch {
      // yt-dlp may print progress lines too; keep looking.
    }
  }
  throw new Error('İndirilen dosyanın yolu yt-dlp çıktısından bulunamadı.')
}

async function downloadThumbnail(url, destination) {
  if (!url) return null
  const response = await fetch(url)
  if (!response.ok) return null
  const buffer = Buffer.from(await response.arrayBuffer())
  await fs.writeFile(destination, buffer)
  return destination
}

async function inspectYouTube(source) {
  const { stdout } = await run('yt-dlp', ['--dump-single-json', '--no-warnings', '--skip-download', source], { quiet: true })
  return JSON.parse(stdout)
}

async function downloadYouTubeMaster(source, outputDir) {
  const template = path.join(outputDir, 'master.%(ext)s')
  const { stdout } = await run('yt-dlp', [
    '--no-playlist',
    '-f', 'bv*+ba/b',
    '--merge-output-format', 'mkv',
    '--print', 'after_move:filepath',
    '-o', template,
    source,
  ])
  return lastExistingPrintedPath(stdout)
}

async function downloadYouTubeSubtitles(source, outputDir) {
  try {
    await run('yt-dlp', [
      '--no-playlist',
      '--skip-download',
      '--write-subs',
      '--write-auto-subs',
      '--sub-langs', 'all,-live_chat',
      '--sub-format', 'vtt',
      '-o', path.join(outputDir, 'subtitles'),
      source,
    ])
  } catch (error) {
    console.warn(`Altyazılar tamamen alınamadı: ${error.message}`)
  }

  const files = await fs.readdir(outputDir)
  return files.filter((name) => name.startsWith('subtitles.') && name.endsWith('.vtt')).map((name) => {
    const language = name.slice('subtitles.'.length, -'.vtt'.length)
    return { id: `sub-${safeId(language)}`, language, label: language, file: name }
  })
}

async function downloadYouTubeAudioTracks(source, info, outputDir) {
  const selected = bestAudioTracks(info.formats || [])
  const tracks = []

  for (let index = 0; index < selected.length; index += 1) {
    const format = selected[index]
    const language = format.language || 'und'
    const label = trackName(format) || language
    const base = `audio.${String(index + 1).padStart(2, '0')}.${safeId(language)}.${safeId(label)}`
    const template = path.join(outputDir, `${base}.source.%(ext)s`)

    try {
      const { stdout } = await run('yt-dlp', [
        '--no-playlist',
        '-f', String(format.format_id),
        '--print', 'after_move:filepath',
        '-o', template,
        source,
      ])
      const sourceAudio = await lastExistingPrintedPath(stdout)
      const targetName = `${base}.m4a`
      const target = path.join(outputDir, targetName)
      await run('ffmpeg', ['-y', '-i', sourceAudio, '-vn', '-c:a', 'aac', '-b:a', '192k', '-movflags', '+faststart', target])
      await fs.rm(sourceAudio, { force: true })
      tracks.push({
        id: `audio-${index + 1}`,
        language,
        label,
        file: targetName,
        channels: format.audio_channels || null,
        sourceFormatId: String(format.format_id),
        default: index === 0,
      })
    } catch (error) {
      console.warn(`Ses parçası atlandı (${language} / ${label}): ${error.message}`)
    }
  }

  return tracks
}

async function extractLocalAudioTracks(source, outputDir) {
  const probe = await ffprobeJson(source)
  const streams = (probe.streams || []).filter((stream) => stream.codec_type === 'audio')
  const tracks = []

  for (let index = 0; index < streams.length; index += 1) {
    const stream = streams[index]
    const language = stream.tags?.language || 'und'
    const label = clean(stream.tags?.title || `Audio ${index + 1}`)
    const targetName = `audio.${String(index + 1).padStart(2, '0')}.${safeId(language)}.${safeId(label)}.m4a`
    const target = path.join(outputDir, targetName)
    await run('ffmpeg', [
      '-y', '-i', source,
      '-map', `0:a:${index}`,
      '-vn', '-c:a', 'aac', '-b:a', '192k',
      '-movflags', '+faststart',
      target,
    ])
    tracks.push({
      id: `audio-${index + 1}`,
      language,
      label,
      file: targetName,
      channels: stream.channels || null,
      sourceCodec: stream.codec_name || null,
      default: Boolean(stream.disposition?.default) || (index === 0 && !streams.some((item) => item.disposition?.default)),
    })
  }

  return tracks
}

async function encodeQualities(masterPath, outputDir) {
  const probe = await ffprobeJson(masterPath)
  const video = (probe.streams || []).find((stream) => stream.codec_type === 'video')
  if (!video) throw new Error('Kaynakta video stream bulunamadı.')

  const sourceHeight = Number(video.height || 0)
  const sourceWidth = Number(video.width || 0)
  const qualities = {}

  for (const preset of QUALITY_PRESETS) {
    if (sourceHeight && preset.height > sourceHeight) continue
    const name = `video.${preset.label}.mp4`
    const destination = path.join(outputDir, name)
    await run('ffmpeg', [
      '-y', '-i', masterPath,
      '-map', '0:v:0', '-map', '0:a:0?',
      '-vf', `scale=-2:${preset.height}`,
      '-c:v', 'libx264', '-preset', 'medium', '-crf', String(preset.crf),
      '-c:a', 'aac', '-b:a', preset.audio,
      '-movflags', '+faststart',
      destination,
    ])
    const outProbe = await ffprobeJson(destination)
    const outVideo = (outProbe.streams || []).find((stream) => stream.codec_type === 'video')
    const stat = await fs.stat(destination)
    qualities[preset.label] = {
      file: name,
      width: Number(outVideo?.width || 0),
      height: Number(outVideo?.height || preset.height),
      codec: outVideo?.codec_name || 'h264',
      sizeBytes: stat.size,
    }
  }

  return { qualities, sourceWidth, sourceHeight, probe }
}

async function copyOriginal(source, outputDir, preferredName = null) {
  const extension = path.extname(source) || '.bin'
  const name = preferredName || `original${extension}`
  const destination = path.join(outputDir, name)
  if (path.resolve(source) !== path.resolve(destination)) await fs.copyFile(source, destination)
  const stat = await fs.stat(destination)
  return { file: name, sizeBytes: stat.size }
}

function channelFromYouTube(info = {}) {
  return {
    id: info.channel_id || info.uploader_id || safeId(info.channel || info.uploader || 'unknown-channel'),
    name: info.channel || info.uploader || 'Bilinmeyen Kanal',
    url: info.channel_url || info.uploader_url || null,
    followerCount: info.channel_follower_count || null,
  }
}

export async function ingestSource(source, { workRoot }) {
  const youtubeLike = isHttpUrl(source)
  const info = youtubeLike ? await inspectYouTube(source) : null
  const id = youtubeLike
    ? `yt-${safeId(info.id || source)}`
    : `local-${Date.now()}-${safeId(path.basename(source, path.extname(source)))}`
  const outputDir = path.join(workRoot, id)
  await fs.mkdir(outputDir, { recursive: true })

  let masterPath
  let audioTracks
  let subtitles = []
  let thumbnail = null

  if (youtubeLike) {
    masterPath = await downloadYouTubeMaster(source, outputDir)
    audioTracks = await downloadYouTubeAudioTracks(source, info, outputDir)
    subtitles = await downloadYouTubeSubtitles(source, outputDir)
    const thumbnailPath = path.join(outputDir, 'thumbnail.jpg')
    if (await downloadThumbnail(info.thumbnail, thumbnailPath)) thumbnail = 'thumbnail.jpg'
  } else {
    await fs.access(source)
    masterPath = source
    audioTracks = await extractLocalAudioTracks(source, outputDir)
  }

  const { qualities, sourceWidth, sourceHeight, probe } = await encodeQualities(masterPath, outputDir)
  const original = await copyOriginal(masterPath, outputDir, youtubeLike ? `original${path.extname(masterPath) || '.mkv'}` : null)
  const durationSeconds = Number(info?.duration || probe.format?.duration || 0)
  const channel = youtubeLike ? channelFromYouTube(info) : { id: 'my-uploads', name: 'Benim Videolarım', url: null }

  const manifest = {
    schemaVersion: 1,
    id,
    title: info?.title || path.basename(source, path.extname(source)),
    description: info?.description || '',
    source: youtubeLike ? {
      type: 'youtube',
      url: source,
      videoId: info?.id || null,
      webpageUrl: info?.webpage_url || source,
      publishedAt: info?.upload_date || null,
    } : {
      type: 'local',
      originalName: path.basename(source),
    },
    channel,
    durationSeconds,
    dimensions: { width: sourceWidth, height: sourceHeight },
    thumbnail,
    original,
    qualities,
    audioTracks,
    subtitles,
    processing: {
      engine: 'local-uploader',
      ffmpeg: true,
      githubActionsTranscode: false,
      completedAt: new Date().toISOString(),
    },
  }

  await fs.writeFile(path.join(outputDir, 'manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`)
  return { id, outputDir, manifest }
}
