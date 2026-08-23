export const QUALITY_ORDER = ['original', '1080p', '720p', '480p', '360p']

export function createVideoRecord(input) {
  return {
    id: input.id,
    title: input.title,
    description: input.description || '',
    channelId: input.channelId,
    channelName: input.channelName,
    source: input.source || { type: 'manual' },
    publishedAt: input.publishedAt || null,
    archivedAt: input.archivedAt || new Date().toISOString(),
    durationSeconds: input.durationSeconds || 0,
    thumbnail: input.thumbnail || null,
    subtitles: input.subtitles || [],
    qualities: input.qualities || {},
    status: input.status || 'processing',
  }
}

export class StorageProvider {
  async listVideos() { throw new Error('listVideos() not implemented') }
  async getPlaybackUrl() { throw new Error('getPlaybackUrl() not implemented') }
  async getDownloadUrl() { throw new Error('getDownloadUrl() not implemented') }
}

// Releases will be accessed through a trusted backend/worker. Never place a
// GitHub token in the Android/PWA frontend bundle.
export class GitHubReleaseStorage extends StorageProvider {
  constructor({ apiBase = '/api' } = {}) {
    super()
    this.apiBase = apiBase
  }

  async listVideos() {
    const response = await fetch(`${this.apiBase}/videos`)
    if (!response.ok) throw new Error('Video catalog could not be loaded')
    return response.json()
  }

  async getPlaybackUrl(videoId, quality = 'auto') {
    const response = await fetch(`${this.apiBase}/videos/${encodeURIComponent(videoId)}/play?quality=${encodeURIComponent(quality)}`)
    if (!response.ok) throw new Error('Playback URL could not be created')
    return response.json()
  }

  async getDownloadUrl(videoId, quality = 'original') {
    const response = await fetch(`${this.apiBase}/videos/${encodeURIComponent(videoId)}/download?quality=${encodeURIComponent(quality)}`)
    if (!response.ok) throw new Error('Download URL could not be created')
    return response.json()
  }
}
