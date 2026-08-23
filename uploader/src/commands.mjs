import { spawn } from 'node:child_process'

export async function run(command, args = [], options = {}) {
  const { cwd, env = process.env, quiet = false } = options
  return await new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      cwd,
      env,
      shell: false,
      windowsHide: true,
    })

    let stdout = ''
    let stderr = ''

    child.stdout.on('data', (chunk) => {
      const text = chunk.toString()
      stdout += text
      if (!quiet) process.stdout.write(text)
    })

    child.stderr.on('data', (chunk) => {
      const text = chunk.toString()
      stderr += text
      if (!quiet) process.stderr.write(text)
    })

    child.on('error', reject)
    child.on('close', (code) => {
      if (code === 0) return resolve({ stdout, stderr })
      const error = new Error(`${command} exited with code ${code}`)
      error.code = code
      error.stdout = stdout
      error.stderr = stderr
      reject(error)
    })
  })
}

export async function assertTool(command, versionArg = '--version') {
  try {
    const { stdout, stderr } = await run(command, [versionArg], { quiet: true })
    return (stdout || stderr).split(/\r?\n/)[0].trim()
  } catch {
    throw new Error(`${command} bulunamadı. YTClone Uploader çalışmadan önce ${command} PATH içinde olmalı.`)
  }
}

export async function ffprobeJson(filePath) {
  const { stdout } = await run('ffprobe', [
    '-v', 'error',
    '-show_streams',
    '-show_format',
    '-of', 'json',
    filePath,
  ], { quiet: true })
  return JSON.parse(stdout)
}
