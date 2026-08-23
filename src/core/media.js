export const QUALITY_ORDER = ['original', '1080p', '720p', '480p', '360p']

export function createVideoRecord(input) {
  const audioTracks = input.audioTracks || []
  return {
    id: input.id,
    title: input.title,
    description: input.description || '',
    channelId: input.channelId || input.channel?.id || null,
    channelName: input.channelName || input.channel?.name || '',
    channel: input.channel || null,
    source: input.source || { type: 'manual' },
    publishedAt: input.publishedAt || input.source?.publishedAt || null,
    archivedAt: input.archivedAt || new Date().toISOString(),
    durationSeconds: input.durationSeconds || 0,
    thumbnail: input.thumbnail || null,
    original: input.original || null,
    subtitles: input.subtitles || [],
    qualities: input.qualities || {},
    audioTracks,
    defaultAudioTrackId: input.defaultAudioTrackId
      || audioTracks.find((track) => track.default)?.id
      || audioTracks[0]?.id
      || null,
    processing: input.processing || null,
    status: input.status || 'processing',
  }
}

export function availableQualities(video) {
  return QUALITY_ORDER.filter((quality) => quality === 'original'
    ? Boolean(video?.original)
    : Boolean(video?.qualities?.[quality]))
}

export function availableAudioTracks(video) {
  return (video?.audioTracks || []).map((track) => ({
    id: track.id,
    language: track.language || 'und',
    label: track.label || track.language || 'Audio',
    default: Boolean(track.default),
  }))
}

export class StorageProvider {
  async listVideos() { throw new Error('listVideos() not implemented') }
  async getPlaybackUrl() { throw new Error('getPlaybackUrl() not implemented') }
  async getDownloadUrl() { throw new Error('getDownloadUrl() not implemented') }
  async getAudioTrackUrl() { throw new Error('getAudioTrackUrl() not implemented') }
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

  async getPlaybackUrl(videoId, quality = 'auto', audioTrackId = null) {
    const params = new URLSearchParams({ quality })
    if (audioTrackId) params.set('audio', audioTrackId)
    const response = await fetch(`${this.apiBase}/videos/${encodeURIComponent(videoId)}/play?${params}`)
    if (!response.ok) throw new Error('Playback URL could not be created')
    return response.json()
  }

  async getDownloadUrl(videoId, quality = 'original') {
    const response = await fetch(`${this.apiBase}/videos/${encodeURIComponent(videoId)}/download?quality=${encodeURIComponent(quality)}`)
    if (!response.ok) throw new Error('Download URL could not be created')
    return response.json()
  }

  async getAudioTrackUrl(videoId, audioTrackId) {
    const response = await fetch(`${this.apiBase}/videos/${encodeURIComponent(videoId)}/audio/${encodeURIComponent(audioTrackId)}`)
    if (!response.ok) throw new Error('Audio track URL could not be created')
    return response.json()
  }
}
