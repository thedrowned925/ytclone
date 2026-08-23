import { useEffect, useMemo, useState } from 'react'

const videos = [
  {
    id: 'v1',
    title: 'Five Nights at Freddy’s — Full Timeline',
    channel: 'Lore Archive',
    duration: '1:43:26',
    meta: '2 gün önce',
    progress: 72,
    palette: 'linear-gradient(135deg,#4f1725,#0d0d0f 68%)',
    accent: '#ff3158',
  },
  {
    id: 'v2',
    title: 'Dark Deception — Chapter 1 Türkçe Dublaj',
    channel: 'Odium Studios',
    duration: '28:14',
    meta: '6 gün önce',
    progress: 0,
    palette: 'linear-gradient(135deg,#422083,#101017 70%)',
    accent: '#9b6cff',
  },
  {
    id: 'v3',
    title: 'Resident Evil 4 — Sinematik Arşiv',
    channel: 'Game Cinema',
    duration: '2:11:08',
    meta: '1 hafta önce',
    progress: 31,
    palette: 'linear-gradient(135deg,#385948,#111514 68%)',
    accent: '#6dd19d',
  },
  {
    id: 'v4',
    title: 'Analog Horror Collection #04',
    channel: 'Archive 925',
    duration: '47:52',
    meta: '2 hafta önce',
    progress: 100,
    palette: 'linear-gradient(135deg,#66502a,#11100d 70%)',
    accent: '#e2bb69',
  },
]

const navItems = [
  ['home', 'Ana Sayfa'],
  ['shorts', 'Shorts'],
  ['plus', 'Ekle'],
  ['channels', 'Kanallar'],
  ['library', 'Kitaplık'],
]

function Icon({ name, size = 24, filled = false }) {
  const common = { width: size, height: size, viewBox: '0 0 24 24', fill: filled ? 'currentColor' : 'none', stroke: 'currentColor', strokeWidth: 1.9, strokeLinecap: 'round', strokeLinejoin: 'round', 'aria-hidden': true }
  const icons = {
    home: <><path d="M3 10.5 12 3l9 7.5"/><path d="M5.5 9.5V21h13V9.5"/><path d="M9.5 21v-6h5v6"/></>,
    shorts: <><path d="M9 3.4 15.8 6a2.2 2.2 0 0 1 .3 4l-7.5 4a2.2 2.2 0 0 0 .2 4l6.3 2.6"/><path d="M10 9.8v4.4l4-2.2-4-2.2Z"/></>,
    plus: <><circle cx="12" cy="12" r="9"/><path d="M12 8v8M8 12h8"/></>,
    channels: <><rect x="3" y="5" width="18" height="14" rx="3"/><path d="m10 9 5 3-5 3V9Z"/></>,
    library: <><path d="M4 4v16M8 4v16M12 6v14M16 5l4 14"/></>,
    search: <><circle cx="11" cy="11" r="6.5"/><path d="m16 16 4 4"/></>,
    cast: <><path d="M4 18h.01M4 13a5 5 0 0 1 5 5M4 8a10 10 0 0 1 10 10"/><path d="M9 5h8a3 3 0 0 1 3 3v7"/></>,
    bell: <><path d="M18 9a6 6 0 0 0-12 0c0 7-3 7-3 8h18c0-1-3-1-3-8"/><path d="M10 21h4"/></>,
    dots: <><circle cx="5" cy="12" r="1" fill="currentColor" stroke="none"/><circle cx="12" cy="12" r="1" fill="currentColor" stroke="none"/><circle cx="19" cy="12" r="1" fill="currentColor" stroke="none"/></>,
    download: <><path d="M12 3v12"/><path d="m7 10 5 5 5-5"/><path d="M5 21h14"/></>,
    clock: <><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></>,
    heart: <><path d="M20.8 4.8a5.3 5.3 0 0 0-7.5 0L12 6.1l-1.3-1.3a5.3 5.3 0 0 0-7.5 7.5L12 21l8.8-8.7a5.3 5.3 0 0 0 0-7.5Z"/></>,
    chevron: <path d="m9 18 6-6-6-6"/>,
    close: <><path d="m6 6 12 12M18 6 6 18"/></>,
  }
  return <svg {...common}>{icons[name] || icons.home}</svg>
}

function App() {
  const [tab, setTab] = useState('home')
  const [selected, setSelected] = useState(null)
  const [query, setQuery] = useState('')
  const [searchOpen, setSearchOpen] = useState(false)
  const [installPrompt, setInstallPrompt] = useState(null)

  useEffect(() => {
    const handler = (event) => {
      event.preventDefault()
      setInstallPrompt(event)
    }
    window.addEventListener('beforeinstallprompt', handler)
    return () => window.removeEventListener('beforeinstallprompt', handler)
  }, [])

  const filtered = useMemo(() => {
    if (!query.trim()) return videos
    const q = query.toLocaleLowerCase('tr')
    return videos.filter((v) => `${v.title} ${v.channel}`.toLocaleLowerCase('tr').includes(q))
  }, [query])

  const install = async () => {
    if (!installPrompt) return
    await installPrompt.prompt()
    setInstallPrompt(null)
  }

  return (
    <div className="app-shell">
      <header className="topbar">
        <button className="brand" onClick={() => setTab('home')} aria-label="YTClone ana sayfa">
          <span className="brand-mark"><span /></span>
          <strong>YTClone</strong>
          <em>925</em>
        </button>
        <div className="top-actions">
          <button className="icon-button"><Icon name="cast" /></button>
          <button className="icon-button"><Icon name="bell" /></button>
          <button className="icon-button" onClick={() => setSearchOpen(true)}><Icon name="search" /></button>
          <button className="avatar">H</button>
        </div>
      </header>

      {installPrompt && (
        <button className="install-banner" onClick={install}>
          <span><b>YTClone’u Android’e kur</b><small>Uygulama gibi tam ekran kullan</small></span>
          <span>Kur</span>
        </button>
      )}

      <main>
        {tab === 'home' && <Home videos={filtered} onOpen={setSelected} />}
        {tab === 'shorts' && <EmptyState title="Shorts" text="Dikey videolar burada tam ekran kaydırmalı oynayacak." />}
        {tab === 'plus' && <UploadPanel />}
        {tab === 'channels' && <Channels />}
        {tab === 'library' && <Library videos={videos} onOpen={setSelected} />}
      </main>

      <nav className="bottom-nav">
        {navItems.map(([id, label]) => (
          <button key={id} className={tab === id ? 'active' : ''} onClick={() => setTab(id)}>
            <span className={id === 'plus' ? 'nav-plus' : ''}><Icon name={id} size={id === 'plus' ? 30 : 23} filled={tab === id && id === 'home'} /></span>
            <small>{label}</small>
          </button>
        ))}
      </nav>

      {selected && <PlayerSheet video={selected} onClose={() => setSelected(null)} />}
      {searchOpen && (
        <div className="search-screen">
          <div className="search-row">
            <button className="icon-button" onClick={() => setSearchOpen(false)}><Icon name="close" /></button>
            <input autoFocus value={query} onChange={(e) => setQuery(e.target.value)} placeholder="YTClone’da ara" />
          </div>
          <div className="search-results">
            {filtered.map((v) => <CompactVideo key={v.id} video={v} onOpen={setSelected} />)}
          </div>
        </div>
      )}
    </div>
  )
}

function Home({ videos, onOpen }) {
  const chips = ['Tümü', 'Oyun', 'Müzik', 'Korku', 'Dublaj', 'Belgesel', 'Son eklenenler']
  return <>
    <div className="chips">{chips.map((chip, i) => <button className={i === 0 ? 'selected' : ''} key={chip}>{chip}</button>)}</div>
    <section className="feed">
      {videos.map((video) => <VideoCard key={video.id} video={video} onOpen={onOpen} />)}
    </section>
  </>
}

function VideoCard({ video, onOpen }) {
  return <article className="video-card">
    <button className="thumbnail" style={{ background: video.palette }} onClick={() => onOpen(video)}>
      <span className="thumb-glow" style={{ background: video.accent }} />
      <span className="thumb-symbol">▶</span>
      <span className="duration">{video.duration}</span>
      {video.progress > 0 && video.progress < 100 && <span className="progress" style={{ width: `${video.progress}%` }} />}
    </button>
    <div className="video-info">
      <div className="channel-avatar" style={{ background: video.accent }}>{video.channel.slice(0, 1)}</div>
      <button className="metadata" onClick={() => onOpen(video)}>
        <h2>{video.title}</h2>
        <p>{video.channel} · {video.meta}</p>
      </button>
      <button className="icon-button"><Icon name="dots" /></button>
    </div>
  </article>
}

function CompactVideo({ video, onOpen }) {
  return <button className="compact-video" onClick={() => onOpen(video)}>
    <span className="compact-thumb" style={{ background: video.palette }}><small>{video.duration}</small></span>
    <span><b>{video.title}</b><small>{video.channel}</small></span>
  </button>
}

function PlayerSheet({ video, onClose }) {
  const qualities = ['Otomatik', '1080p', '720p', '480p', '360p']
  const [quality, setQuality] = useState('Otomatik')
  return <div className="player-sheet">
    <div className="player-stage" style={{ background: video.palette }}>
      <button className="player-close" onClick={onClose}><Icon name="close" /></button>
      <span className="player-play">▶</span>
      <div className="fake-timeline"><span style={{ width: `${video.progress || 8}%` }} /></div>
    </div>
    <div className="player-content">
      <h1>{video.title}</h1>
      <p className="player-meta">{video.channel} · {video.meta}</p>
      <div className="action-strip">
        <button><Icon name="heart" /><span>Favori</span></button>
        <button><Icon name="clock" /><span>Sonra izle</span></button>
        <button><Icon name="download" /><span>İndir</span></button>
      </div>
      <div className="quality-card">
        <div><b>Video kalitesi</b><small>Kaynak çözünürlüğe göre sürümler otomatik oluşacak.</small></div>
        <select value={quality} onChange={(e) => setQuality(e.target.value)}>{qualities.map((q) => <option key={q}>{q}</option>)}</select>
      </div>
      <button className="channel-row"><span className="channel-avatar large" style={{ background: video.accent }}>{video.channel.slice(0, 1)}</span><span><b>{video.channel}</b><small>Kanalı aç</small></span><Icon name="chevron" /></button>
      <section className="description-card"><b>Açıklama</b><p>Bu alan videonun orijinal açıklamasını, yayın tarihini, etiketlerini ve arşiv bilgilerini gösterecek.</p></section>
    </div>
  </div>
}

function UploadPanel() {
  return <section className="page-panel upload-panel">
    <div className="upload-icon"><Icon name="plus" size={42} /></div>
    <h1>Video ekle</h1>
    <p>YouTube bağlantısı yapıştır veya cihazından bir video seç. YTClone kanal, başlık, thumbnail, kalite ve altyazı işlemlerini otomatikleştirecek.</p>
    <label className="url-box"><input placeholder="https://youtube.com/watch?v=…" /><button>Arşivle</button></label>
    <button className="secondary-button">Cihazdan video seç</button>
  </section>
}

function Channels() {
  const channels = [
    ['O', 'Odium Studios', '12 video', '#7256ff'],
    ['L', 'Lore Archive', '38 video', '#f54264'],
    ['G', 'Game Cinema', '17 video', '#45a477'],
    ['A', 'Archive 925', '64 video', '#b08a43'],
  ]
  return <section className="page-panel"><h1>Kanallar</h1><p className="muted">Arşivlediğin videolar kaynak kanallarına göre otomatik gruplanır.</p><div className="channel-list">{channels.map(([letter, name, count, color]) => <button key={name}><span className="channel-avatar large" style={{ background: color }}>{letter}</span><span><b>{name}</b><small>{count}</small></span><Icon name="chevron" /></button>)}</div></section>
}

function Library({ videos, onOpen }) {
  return <section className="page-panel">
    <h1>Kitaplık</h1>
    <div className="library-grid">
      <button><Icon name="clock" /><span><b>Geçmiş</b><small>Kaldığın yerden devam et</small></span></button>
      <button><Icon name="download" /><span><b>İndirilenler</b><small>Çevrimdışı videolar</small></span></button>
      <button><Icon name="heart" /><span><b>Favoriler</b><small>Kaydettiklerin</small></span></button>
    </div>
    <h2 className="section-title">İzlemeye devam et</h2>
    {videos.filter((v) => v.progress > 0 && v.progress < 100).map((v) => <CompactVideo key={v.id} video={v} onOpen={onOpen} />)}
  </section>
}

function EmptyState({ title, text }) {
  return <section className="page-panel empty-state"><h1>{title}</h1><p>{text}</p><span>V1</span></section>
}

export default App
