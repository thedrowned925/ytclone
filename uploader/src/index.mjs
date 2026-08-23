import fs from 'node:fs/promises'
import path from 'node:path'
import { assertTool } from './commands.mjs'
import { ingestSource } from './pipeline.mjs'
import { publishDirectory } from './github.mjs'

function parseArgs(argv) {
  const result = {
    source: null,
    repo: process.env.YTCLONE_MEDIA_REPO || null,
    workRoot: process.env.YTCLONE_WORK_DIR || path.resolve('work'),
    upload: true,
    check: false,
  }

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index]
    if (arg === '--check') result.check = true
    else if (arg === '--no-upload') result.upload = false
    else if (arg === '--repo') result.repo = argv[++index]
    else if (arg === '--work-dir') result.workRoot = path.resolve(argv[++index])
    else if (!arg.startsWith('--') && !result.source) result.source = arg
    else throw new Error(`Bilinmeyen argüman: ${arg}`)
  }
  return result
}

async function checkTools() {
  const tools = {}
  tools.ffmpeg = await assertTool('ffmpeg', '-version')
  tools.ffprobe = await assertTool('ffprobe', '-version')
  tools.ytDlp = await assertTool('yt-dlp', '--version')
  return tools
}

async function main() {
  const options = parseArgs(process.argv.slice(2))
  console.log('YTClone Uploader — local ingest engine')
  const tools = await checkTools()
  console.log(`✓ ${tools.ffmpeg}`)
  console.log(`✓ ${tools.ffprobe}`)
  console.log(`✓ yt-dlp ${tools.ytDlp}`)

  if (options.check) return
  if (!options.source) {
    console.log('\nKullanım:')
    console.log('  npm run ingest -- "https://youtube.com/watch?v=..." --repo owner/media-repo')
    console.log('  npm run ingest -- "C:\\Videos\\video.mkv" --repo owner/media-repo')
    console.log('  npm run ingest -- "video.mkv" --no-upload')
    process.exitCode = 1
    return
  }

  await fs.mkdir(options.workRoot, { recursive: true })
  console.log(`\nİşleniyor: ${options.source}`)
  const job = await ingestSource(options.source, { workRoot: options.workRoot })
  console.log(`\n✓ Yerel paket hazır: ${job.outputDir}`)
  console.log(`  Kaliteler: ${Object.keys(job.manifest.qualities).join(', ') || 'yok'}`)
  console.log(`  Ses parçaları: ${job.manifest.audioTracks.length}`)
  console.log(`  Altyazılar: ${job.manifest.subtitles.length}`)

  if (!options.upload) {
    console.log('GitHub yüklemesi --no-upload nedeniyle atlandı.')
    return
  }
  if (!options.repo) {
    console.log('YTCLONE_MEDIA_REPO/--repo belirtilmediği için GitHub yüklemesi atlandı; yerel paket korunuyor.')
    return
  }

  const token = process.env.YTCLONE_GITHUB_TOKEN
  const published = await publishDirectory({
    outputDir: job.outputDir,
    manifest: job.manifest,
    repo: options.repo,
    token,
  })
  await fs.writeFile(path.join(job.outputDir, 'publish.json'), `${JSON.stringify(published, null, 2)}\n`)
  console.log(`\n✓ Yayınlandı: ${published.url}`)
}

main().catch((error) => {
  console.error(`\nYTClone Uploader hata: ${error.message}`)
  process.exitCode = 1
})
