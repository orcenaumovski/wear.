import { useEffect, useMemo, useRef, useState } from 'react'
import './App.css'

type Item = {
  id: number
  name: string | null
  category: string | null
  tagsJson: string | null
  colorsJson: string | null
  imagePath: string
  imageUrl?: string | null
  createdAtEpochMs: number
  analysisError?: string | null
}

type OutfitPiece = {
  itemId: number
  role: string
}

type OutfitPlan = {
  name: string
  reasoning?: string | null
  items?: OutfitPiece[]
}

type OutfitResponse = {
  outfits: OutfitPlan[]
}

type SavedOutfit = {
  id: number
  name: string
  createdAtEpochMs: number
  items: OutfitPiece[]
}

type SavedOutfitsResponse = {
  outfits: SavedOutfit[]
}

type User = {
  id: number
  username: string
}

type AuthSession = {
  token: string
  user: User
}

type AuthResponse = AuthSession

type MainPage = 'craft' | 'pieces' | 'closet'
type AuthMode = 'login' | 'register'

const API_ORIGIN = 'http://127.0.0.1:8080'
const API_BASE = `${API_ORIGIN}/api`
const MAX_IMAGE_DIMENSION = 1400
const AUTH_STORAGE_KEY = 'wear.auth'
const MAIN_PAGE_PATHS: Record<MainPage, string> = {
  craft: '/craft',
  pieces: '/my-pieces',
  closet: '/my-closet',
}

function pageFromPath(pathname: string): MainPage {
  if (pathname === MAIN_PAGE_PATHS.pieces) return 'pieces'
  if (pathname === MAIN_PAGE_PATHS.closet) return 'closet'
  return 'craft'
}

function authModeFromPath(pathname: string): AuthMode {
  return pathname === '/register' ? 'register' : 'login'
}

function storedSession() {
  try {
    const raw = window.sessionStorage.getItem(AUTH_STORAGE_KEY) ?? window.localStorage.getItem(AUTH_STORAGE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as AuthSession
    return parsed?.token && parsed?.user?.username ? parsed : null
  } catch {
    return null
  }
}

function parseJsonList(json: string | null | undefined) {
  if (!json) return []

  try {
    const parsed = JSON.parse(json)
    return Array.isArray(parsed) ? parsed.filter((value): value is string => typeof value === 'string') : []
  } catch {
    return []
  }
}

function cleanRole(role: string | null | undefined) {
  return (role ?? '').trim().replace(/[_-]+/g, ' ').replace(/\s+/g, ' ')
}

function normalizedRoleName(role: string) {
  const normalized = role.toLowerCase()

  if (normalized === 'sneaker' || normalized === 'sneakers' || normalized === 'shoe' || normalized === 'shoes') {
    return 'shoewear'
  }

  if (normalized === 'shirt' || normalized === 'shirts' || normalized === 'tee' || normalized === 'tees') {
    return 't-shirts'
  }

  return role
}

function roleTitleFor(role: string | null | undefined) {
  const clean = normalizedRoleName(cleanRole(role))
  if (!clean || clean.toLowerCase() === 'unknown') return 'Unsorted'

  return clean
    .split(' ')
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1).toLowerCase())
    .join(' ')
}

function needsRole(role: string | null | undefined) {
  const clean = cleanRole(role)
  return !clean || clean.toLowerCase() === 'unknown'
}

function isModelImageErrorText(value: string | null | undefined) {
  const normalized = (value ?? '').trim().toLowerCase()
  return (
    normalized === 'no image provided' ||
    normalized === 'no photo' ||
    normalized === 'missing image' ||
    normalized === 'missing photo' ||
    normalized.startsWith('error:no') ||
    normalized.startsWith('error:missing')
  )
}

function hasModelImageError(item: Item) {
  return (
    isModelImageErrorText(item.name) ||
    parseJsonList(item.tagsJson).some(isModelImageErrorText) ||
    parseJsonList(item.colorsJson).some(isModelImageErrorText)
  )
}

function analysisMessageFor(item: Item) {
  if (item.analysisError) return item.analysisError
  if (hasModelImageError(item)) return 'Previous analysis did not read this image.'
  return null
}

function groupItemsByRole(items: Item[]) {
  const groups = new Map<string, Item[]>()

  for (const item of items) {
    const roleTitle = roleTitleFor(item.category)
    const roleItems = groups.get(roleTitle) ?? []
    roleItems.push(item)
    groups.set(roleTitle, roleItems)
  }

  return Array.from(groups, ([role, groupedItems]) => ({ role, items: groupedItems }))
}

function fileStem(name: string) {
  const dot = name.lastIndexOf('.')
  return dot > 0 ? name.slice(0, dot) : name
}

async function imageBlobToJpegFile(blob: Blob, name: string) {
  const bitmap = await createImageBitmap(blob)
  const scale = Math.min(1, MAX_IMAGE_DIMENSION / Math.max(bitmap.width, bitmap.height))
  const width = Math.max(1, Math.round(bitmap.width * scale))
  const height = Math.max(1, Math.round(bitmap.height * scale))

  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('Could not prepare image for analysis')
  ctx.drawImage(bitmap, 0, 0, width, height)
  bitmap.close()

  const jpegBlob = await new Promise<Blob>((resolve, reject) => {
    canvas.toBlob(
      (result) => {
        if (result) resolve(result)
        else reject(new Error('Could not convert image to JPEG'))
      },
      'image/jpeg',
      0.9,
    )
  })

  return new File([jpegBlob], `${fileStem(name)}.jpg`, { type: 'image/jpeg' })
}

async function fileForUpload(file: File) {
  try {
    return await imageBlobToJpegFile(file, file.name)
  } catch {
    throw new Error(`Could not convert ${file.name} to JPEG. Try exporting it as JPG first.`)
  }
}

async function errorMessageFromResponse(action: string, res: Response) {
  const text = await res.text().catch(() => '')
  if (!text) return `${action} failed: ${res.status}`

  try {
    const parsed = JSON.parse(text) as { message?: string; error?: string; reason?: string }
    const message = parsed.message ?? parsed.reason ?? parsed.error
    if (message) return `${action} failed: ${message}`
  } catch {
    // Fall back to plain response text below.
  }

  return `${action} failed: ${res.status} ${text}`
}

function generatedOutfitKey(outfit: OutfitPlan, index: number) {
  const pieces = (outfit.items ?? []).map((piece) => `${piece.itemId}:${piece.role}`).join('|')
  return `${index}:${outfit.name}:${pieces}`
}

function App() {
  const [session, setSession] = useState<AuthSession | null>(() => storedSession())
  const [showIntro, setShowIntro] = useState(() => window.location.pathname === '/')
  const [authMode, setAuthMode] = useState<AuthMode>(() => authModeFromPath(window.location.pathname))
  const [activePage, setActivePage] = useState<MainPage>(() => pageFromPath(window.location.pathname))
  const [items, setItems] = useState<Item[]>([])
  const [savedOutfits, setSavedOutfits] = useState<SavedOutfit[]>([])
  const [selectedItemIds, setSelectedItemIds] = useState<number[]>([])
  const [showPieceSelector, setShowPieceSelector] = useState(false)
  const knownItemIdsRef = useRef<Set<number>>(new Set())
  const [files, setFiles] = useState<File[]>([])
  const [previewUrls, setPreviewUrls] = useState<string[]>([])
  const [busyAction, setBusyAction] = useState<string | null>(null)
  const [editingRole, setEditingRole] = useState<string | null>(null)
  const [pendingDeleteItem, setPendingDeleteItem] = useState<Item | null>(null)
  const [pendingDeleteOutfit, setPendingDeleteOutfit] = useState<SavedOutfit | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [outfits, setOutfits] = useState<OutfitResponse | null>(null)
  const [savedGeneratedOutfitKeys, setSavedGeneratedOutfitKeys] = useState<string[]>([])
  const [outfitCount, setOutfitCount] = useState(3)
  const [authUsername, setAuthUsername] = useState('')
  const [authPassword, setAuthPassword] = useState('')
  const [stayLoggedIn, setStayLoggedIn] = useState(false)

  const busy = busyAction !== null
  const itemsById = useMemo(() => new Map(items.map((item) => [item.id, item])), [items])
  const closetGroups = useMemo(() => groupItemsByRole(items), [items])
  const selectedCount = selectedItemIds.length

  function setAndStoreSession(nextSession: AuthSession | null, remember = false) {
    setSession(nextSession)
    window.localStorage.removeItem(AUTH_STORAGE_KEY)
    window.sessionStorage.removeItem(AUTH_STORAGE_KEY)
    if (nextSession) {
      const storage = remember ? window.localStorage : window.sessionStorage
      storage.setItem(AUTH_STORAGE_KEY, JSON.stringify(nextSession))
    }
  }

  function navigateTo(page: MainPage) {
    const nextPath = MAIN_PAGE_PATHS[page]
    window.history.pushState(null, '', nextPath)
    setShowIntro(false)
    setActivePage(page)
  }

  function navigateAuth(mode: AuthMode) {
    window.history.pushState(null, '', mode === 'register' ? '/register' : '/login')
    setShowIntro(false)
    setAuthMode(mode)
  }

  function navigateHome() {
    window.history.pushState(null, '', '/')
    setShowIntro(true)
  }

  function enterFromIntro() {
    if (session) {
      navigateTo('craft')
    } else {
      navigateAuth('login')
    }
  }

  function logout() {
    setAndStoreSession(null)
    setItems([])
    setSavedOutfits([])
    setOutfits(null)
    setSelectedItemIds([])
    knownItemIdsRef.current = new Set()
    navigateAuth('login')
  }

  function authHeaders(): HeadersInit {
    return session ? { Authorization: `Bearer ${session.token}` } : {}
  }

  async function apiFetch(path: string, init: RequestInit = {}) {
    const headers = new Headers(init.headers)
    if (session) headers.set('Authorization', `Bearer ${session.token}`)

    const res = await fetch(`${API_BASE}${path}`, {
      ...init,
      headers,
    })

    if (res.status === 401) {
      setAndStoreSession(null)
      navigateAuth('login')
    }
    return res
  }

  function imageSrcFor(item: Item) {
    const base = item.imageUrl?.startsWith('http')
      ? item.imageUrl
      : item.imageUrl?.startsWith('/')
        ? `${API_ORIGIN}${item.imageUrl}`
        : `${API_BASE}/items/${item.id}/image`
    if (!session) return base
    const separator = base.includes('?') ? '&' : '?'
    return `${base}${separator}token=${encodeURIComponent(session.token)}`
  }

  async function refreshItems() {
    if (!session) return
    const res = await apiFetch('/items')
    if (!res.ok) throw new Error(`Failed to load pieces: ${res.status}`)
    const data = (await res.json()) as Item[]
    setItems(data)
  }

  async function refreshSavedOutfits() {
    if (!session) return
    const res = await apiFetch('/outfits')
    if (!res.ok) throw new Error(`Failed to load saved outfits: ${res.status}`)
    const data = (await res.json()) as SavedOutfitsResponse
    setSavedOutfits(data.outfits ?? [])
  }

  async function refreshWorkspace() {
    await Promise.all([refreshItems(), refreshSavedOutfits()])
  }

  useEffect(() => {
    function syncRoute() {
      const isIntroRoute = window.location.pathname === '/'
      setShowIntro(isIntroRoute)
      if (isIntroRoute) return

      if (window.location.pathname === '/login' || window.location.pathname === '/register') {
        setAuthMode(authModeFromPath(window.location.pathname))
      } else {
        setActivePage(pageFromPath(window.location.pathname))
      }
    }

    window.addEventListener('popstate', syncRoute)
    return () => window.removeEventListener('popstate', syncRoute)
  }, [])

  useEffect(() => {
    if (showIntro || !session) return
    refreshWorkspace().catch((e) => setError(String(e)))
    // refreshWorkspace intentionally follows the current logged-in session for this load pass.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [showIntro, session])

  useEffect(() => {
    const itemIds = new Set(items.map((item) => item.id))
    const previousKnownIds = knownItemIdsRef.current

    setSelectedItemIds((current) => {
      const next = current.filter((id) => itemIds.has(id))
      for (const id of itemIds) {
        if (!previousKnownIds.has(id) && !next.includes(id)) {
          next.push(id)
        }
      }
      return next
    })

    knownItemIdsRef.current = itemIds
  }, [items])

  useEffect(() => {
    const nextPreviewUrls = files.map((file) => URL.createObjectURL(file))
    setPreviewUrls(nextPreviewUrls)

    return () => {
      nextPreviewUrls.forEach((url) => URL.revokeObjectURL(url))
    }
  }, [files])

  async function onAuth(e: React.FormEvent) {
    e.preventDefault()
    setBusyAction('auth')
    setError(null)
    setNotice(null)
    try {
      const res = await fetch(`${API_BASE}/auth/${authMode}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: authUsername, password: authPassword }),
      })
      if (!res.ok) {
        throw new Error(await errorMessageFromResponse(authMode === 'register' ? 'Register' : 'Login', res))
      }

      const data = (await res.json()) as AuthResponse
      setAndStoreSession(data, stayLoggedIn)
      setAuthPassword('')
      navigateTo('craft')
    } catch (e) {
      setError(String(e))
    } finally {
      setBusyAction(null)
    }
  }

  async function onUpload(e: React.FormEvent) {
    e.preventDefault()
    if (files.length === 0) return

    setBusyAction('upload')
    setError(null)
    setNotice(null)
    try {
      const form = new FormData()
      const convertedFiles = await Promise.all(files.map(fileForUpload))
      for (const file of convertedFiles) form.append('images', file)

      const res = await apiFetch('/items', { method: 'POST', body: form })
      if (!res.ok) {
        throw new Error(await errorMessageFromResponse('Upload', res))
      }

      setFiles([])
      setOutfits(null)
      setNotice('Piece added.')
      await refreshItems()
    } catch (e) {
      setError(String(e))
    } finally {
      setBusyAction(null)
    }
  }

  async function onAnalyzeItem(item: Item) {
    setBusyAction(`analyze-${item.id}`)
    setError(null)
    setNotice(null)
    try {
      const form = new FormData()
      try {
        const imageRes = await fetch(imageSrcFor(item), { headers: authHeaders() })
        if (imageRes.ok) {
          const blob = await imageRes.blob()
          form.append('image', await imageBlobToJpegFile(blob, `item-${item.id}.jpg`))
        }
      } catch {
        // Fall back to server-side analysis of the stored image if browser conversion fails.
      }

      const res = await apiFetch(`/items/${item.id}/analyze`, {
        method: 'POST',
        body: form.has('image') ? form : undefined,
      })
      if (!res.ok) {
        throw new Error(await errorMessageFromResponse('Analyze', res))
      }

      const updated = (await res.json()) as Item
      setItems((current) => current.map((item) => (item.id === updated.id ? updated : item)))
      if (updated.analysisError) setError(`Analyze failed: ${updated.analysisError}`)
      else setNotice('Piece analyzed.')
      setOutfits(null)
    } catch (e) {
      setError(String(e))
    } finally {
      setBusyAction(null)
    }
  }

  async function onGenerateOutfits() {
    setBusyAction('generate')
    setError(null)
    setNotice(null)
    try {
      const res = await apiFetch(`/outfits/generate?count=${outfitCount}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ itemIds: selectedItemIds }),
      })
      if (!res.ok) {
        throw new Error(await errorMessageFromResponse('Generate', res))
      }

      const data = (await res.json()) as OutfitResponse
      setOutfits(data)
      setSavedGeneratedOutfitKeys([])
    } catch (e) {
      setError(String(e))
    } finally {
      setBusyAction(null)
    }
  }

  async function onSaveOutfit(outfit: OutfitPlan, index: number) {
    const outfitItems = Array.isArray(outfit.items) ? outfit.items : []
    if (outfitItems.length === 0) return

    setBusyAction(`save-${index}`)
    setError(null)
    setNotice(null)
    try {
      const res = await apiFetch('/outfits', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: outfit.name || `Outfit ${index + 1}`, items: outfitItems }),
      })
      if (!res.ok) {
        throw new Error(await errorMessageFromResponse('Save', res))
      }

      const saved = (await res.json()) as SavedOutfit
      setSavedOutfits((current) => [saved, ...current])
      setSavedGeneratedOutfitKeys((current) => {
        const key = generatedOutfitKey(outfit, index)
        return current.includes(key) ? current : [...current, key]
      })
    } catch (e) {
      setError(String(e))
    } finally {
      setBusyAction(null)
    }
  }

  async function onDeleteItem(item: Item) {
    setBusyAction(`delete-${item.id}`)
    setError(null)
    setNotice(null)
    try {
      const res = await apiFetch(`/items/${item.id}`, { method: 'DELETE' })
      if (!res.ok) {
        throw new Error(await errorMessageFromResponse('Delete', res))
      }

      setItems((current) => current.filter((currentItem) => currentItem.id !== item.id))
      setOutfits(null)
      await refreshSavedOutfits()
    } catch (e) {
      setError(String(e))
    } finally {
      setBusyAction(null)
    }
  }

  async function onDeleteSavedOutfit(outfit: SavedOutfit) {
    setBusyAction(`delete-outfit-${outfit.id}`)
    setError(null)
    setNotice(null)
    try {
      const res = await apiFetch(`/outfits/${outfit.id}`, { method: 'DELETE' })
      if (!res.ok) {
        throw new Error(await errorMessageFromResponse('Delete outfit', res))
      }

      setSavedOutfits((current) => current.filter((currentOutfit) => currentOutfit.id !== outfit.id))
    } catch (e) {
      setError(String(e))
    } finally {
      setBusyAction(null)
    }
  }

  function toggleSelectedItem(itemId: number) {
    setSelectedItemIds((current) =>
      current.includes(itemId) ? current.filter((id) => id !== itemId) : [...current, itemId],
    )
  }

  function selectAllItems() {
    setSelectedItemIds(items.map((item) => item.id))
  }

  function clearSelectedItems() {
    setSelectedItemIds([])
  }

  function renderCraftPieceSelector() {
    return (
      <section className="section selectorSection">
        <div className="selectorTopline">
          <button
            className="button primary addPiecesButton"
            type="button"
            onClick={() => setShowPieceSelector((current) => !current)}
            disabled={items.length === 0}
          >
            Add new pieces
          </button>
          <div className="selectorSummary">{selectedCount} of {items.length} pieces selected</div>
        </div>

        {showPieceSelector ? (
          <div className="selectorPanel" data-testid="piece-selector">
            <div className="selectorActions">
              <button className="button smallButton" type="button" onClick={selectAllItems}>
                Select all
              </button>
              <button className="button smallButton" type="button" onClick={clearSelectedItems}>
                Deselect all
              </button>
            </div>
            {items.length === 0 ? (
              <div className="empty compactEmpty">Add a few pieces first.</div>
            ) : (
              <div className="selectorList">
                {items.map((item) => (
                  <label className="selectorItem" key={item.id}>
                    <input
                      className="selectorCheckbox"
                      type="checkbox"
                      checked={selectedItemIds.includes(item.id)}
                      onChange={() => toggleSelectedItem(item.id)}
                    />
                    <img className="selectorThumb" src={imageSrcFor(item)} alt={`${roleTitleFor(item.category)} option`} />
                    <span>{roleTitleFor(item.category)}</span>
                  </label>
                ))}
              </div>
            )}
          </div>
        ) : null}
      </section>
    )
  }

  function renderUploadSection(title: string) {
    return (
      <section className="section">
        <div className="sectionHeader">
          <h2>{title}</h2>
        </div>
        <form className="form" onSubmit={onUpload}>
          <div className="label">
            <input
              className="fileInput"
              id={`closet-upload-${title.replace(/\s+/g, '-').toLowerCase()}`}
              type="file"
              accept="image/*"
              multiple
              onChange={(e) => setFiles(Array.from(e.target.files ?? []))}
            />
            <label className="button browseButton" htmlFor={`closet-upload-${title.replace(/\s+/g, '-').toLowerCase()}`}>
              Stroll through the closet
            </label>
          </div>

          {files.length > 0 ? (
            <div className="previewGrid" aria-label="Selected photos">
              {files.map((file, index) => (
                <div className="previewTile" key={`${file.name}-${file.lastModified}`}>
                  {previewUrls[index] ? <img src={previewUrls[index]} alt="Selected upload" /> : null}
                </div>
              ))}
            </div>
          ) : null}

          <button className="button primary" type="submit" disabled={files.length === 0 || busy}>
            {busyAction === 'upload' ? 'Uploading + analyzing...' : `Upload ${files.length || ''} photo${files.length === 1 ? '' : 's'}`}
          </button>
        </form>
      </section>
    )
  }

  function renderPiecesGrid() {
    if (items.length === 0) {
      return <div className="empty">No pieces yet.</div>
    }

    return (
      <div className="roleSections">
        {closetGroups.map((group) => (
          <section className="roleSection" key={group.role}>
            <div className="roleHeader">
              <h3 className="roleTitle">{group.role}</h3>
              <button
                className="button sectionButton"
                type="button"
                onClick={() => setEditingRole((current) => (current === group.role ? null : group.role))}
                disabled={busy && editingRole !== group.role}
              >
                {editingRole === group.role ? 'Done' : 'Edit'}
              </button>
            </div>
            <div className="roleGrid">
              {group.items.map((item) => {
                const analysisMessage = analysisMessageFor(item)
                const showAnalysisControls = analysisMessage || needsRole(item.category) || hasModelImageError(item)
                const isDeleting = busyAction === `delete-${item.id}`

                return (
                  <article className="itemCard" data-testid="closet-item" key={item.id}>
                    {editingRole === group.role ? (
                      <button
                        className="deleteChip"
                        type="button"
                        aria-label="Remove piece"
                        onClick={() => setPendingDeleteItem(item)}
                        disabled={busy}
                      >
                        {isDeleting ? '...' : 'X'}
                      </button>
                    ) : null}
                    <img className="itemPhoto" src={imageSrcFor(item)} alt={`${group.role} item`} loading="lazy" />
                    {showAnalysisControls ? (
                      <div className="itemBody">
                        {analysisMessage ? <div className="error compact">AI error: {analysisMessage}</div> : null}
                        {needsRole(item.category) || hasModelImageError(item) ? (
                          <button className="button smallButton" onClick={() => onAnalyzeItem(item)} disabled={busy}>
                            {busyAction === `analyze-${item.id}` ? 'Analyzing...' : 'Analyze again'}
                          </button>
                        ) : null}
                      </div>
                    ) : null}
                  </article>
                )
              })}
            </div>
          </section>
        ))}
      </div>
    )
  }

  function renderOutfitCard(outfit: OutfitPlan | SavedOutfit, idx: number, saved: boolean) {
    const outfitItems = Array.isArray(outfit.items) ? outfit.items : []
    const canSave = !saved && outfitItems.length > 0
    const savedOutfit = saved ? (outfit as SavedOutfit) : null
    const generatedKey = saved ? null : generatedOutfitKey(outfit as OutfitPlan, idx)
    const isGeneratedOutfitSaved = generatedKey !== null && savedGeneratedOutfitKeys.includes(generatedKey)

    return (
      <article className="outfitCard" data-testid={saved ? 'saved-outfit-card' : 'outfit-card'} key={`${outfit.name}-${idx}`}>
        <div className="outfitHeader">
          <div>
            <div className="outfitIndex">{saved ? 'Saved outfit' : `Outfit ${idx + 1}`}</div>
            <div className="outfitName">{outfit.name}</div>
          </div>
          {canSave ? (
            <button
              className={isGeneratedOutfitSaved ? 'button smallButton savedButton' : 'button smallButton'}
              type="button"
              onClick={() => onSaveOutfit(outfit, idx)}
              disabled={busy || isGeneratedOutfitSaved}
            >
              {isGeneratedOutfitSaved ? 'Saved!' : busyAction === `save-${idx}` ? 'Saving...' : 'Save outfit'}
            </button>
          ) : null}
          {savedOutfit ? (
            <button
              className="deleteChip outfitDeleteChip"
              type="button"
              aria-label="Remove saved outfit"
              onClick={() => setPendingDeleteOutfit(savedOutfit)}
              disabled={busy}
            >
              {busyAction === `delete-outfit-${savedOutfit.id}` ? '...' : 'X'}
            </button>
          ) : null}
        </div>

        {outfitItems.length > 0 ? (
          <div className="pieceGrid">
            {outfitItems.map((piece, pieceIndex) => {
              const item = itemsById.get(piece.itemId)
              const pieceRole = item ? item.category : piece.role

              return (
                <div className="pieceCard" data-testid="outfit-piece" key={`${piece.itemId}-${piece.role}-${pieceIndex}`}>
                  {item ? (
                    <img className="piecePhoto" src={imageSrcFor(item)} alt={`${roleTitleFor(pieceRole)} item`} loading="lazy" />
                  ) : (
                    <div className="missingPhoto">Missing</div>
                  )}
                  <div className="pieceInfo">
                    <span className="pill role">{roleTitleFor(pieceRole)}</span>
                  </div>
                </div>
              )
            })}
          </div>
        ) : (
          <div className="empty compactEmpty">No pieces saved for this outfit.</div>
        )}
      </article>
    )
  }

  if (showIntro) {
    return (
      <main className="introPage">
        <section className="introPanel" aria-labelledby="intro-title">
          <h1 className="title introLogo" id="intro-title">
            wear.
          </h1>
          <p className="introCopy">
            Turn single clothing-piece photos into a visual closet, then craft outfit ideas from the pieces you already own.
          </p>
          <button className="button primary introButton" type="button" onClick={enterFromIntro}>
            Let's craft some outfits
          </button>
        </section>
      </main>
    )
  }

  if (!session) {
    return (
      <main className="authPage">
        <section className="authPanel" aria-labelledby="auth-title">
          <button className="navLogo authLogo" type="button" onClick={navigateHome} aria-label="Back to intro">
            wear.
          </button>
          <h1 id="auth-title">{authMode === 'register' ? 'Create account' : 'Log in'}</h1>
          <form className="authForm" onSubmit={onAuth}>
            <label className="label">
              Username
              <input className="input" value={authUsername} onChange={(e) => setAuthUsername(e.target.value)} autoComplete="username" />
            </label>
            <label className="label">
              Password
              <input
                className="input"
                type="password"
                value={authPassword}
                onChange={(e) => setAuthPassword(e.target.value)}
                autoComplete={authMode === 'register' ? 'new-password' : 'current-password'}
              />
            </label>
            <label className="stayLoggedInOption">
              <input
                className="selectorCheckbox"
                type="checkbox"
                checked={stayLoggedIn}
                onChange={(e) => setStayLoggedIn(e.target.checked)}
              />
              <span>Stay logged in</span>
            </label>
            <button className="button primary" type="submit" disabled={busy || !authUsername || !authPassword}>
              {busyAction === 'auth' ? 'One moment...' : authMode === 'register' ? 'Register' : 'Log in'}
            </button>
          </form>
          <button
            className="button authSwitch"
            type="button"
            onClick={() => navigateAuth(authMode === 'register' ? 'login' : 'register')}
          >
            {authMode === 'register' ? 'I already have an account' : 'Create a new account'}
          </button>
          {error ? <div className="error">{error}</div> : null}
        </section>
      </main>
    )
  }

  return (
    <div className="appShell">
      <header className="appNav">
        <button className="navLogo" type="button" onClick={navigateHome} aria-label="Back to intro">
          wear.
        </button>
        <nav className="navLinks" aria-label="Main pages">
          <button className={activePage === 'craft' ? 'navButton active' : 'navButton'} type="button" onClick={() => navigateTo('craft')}>
            Craft
          </button>
          <button className={activePage === 'pieces' ? 'navButton active' : 'navButton'} type="button" onClick={() => navigateTo('pieces')}>
            My pieces
          </button>
          <button className={activePage === 'closet' ? 'navButton active' : 'navButton'} type="button" onClick={() => navigateTo('closet')}>
            My closet
          </button>
        </nav>
        <div className="userNav">
          <button className="button sectionButton" type="button" onClick={logout}>
            Log out
          </button>
        </div>
      </header>

      <main className="page">
        {error ? <div className="error">{error}</div> : null}
        {notice ? <div className="notice">{notice}</div> : null}

        {activePage === 'pieces' ? (
          <>
            {renderUploadSection('Add piece')}
            <section className="section">
              <div className="sectionHeader">
                <h2>My pieces</h2>
                <span className="count">{items.length} pieces</span>
              </div>
              {renderPiecesGrid()}
            </section>
          </>
        ) : null}

        {activePage === 'closet' ? (
          <section className="section">
            <div className="sectionHeader">
              <h2>My closet</h2>
              <span className="count">{savedOutfits.length} saved</span>
            </div>
            {savedOutfits.length > 0 ? (
              <div className="outfitGrid">{savedOutfits.map((outfit, idx) => renderOutfitCard(outfit, idx, true))}</div>
            ) : (
              <div className="empty">No saved outfits yet.</div>
            )}
          </section>
        ) : null}

        {activePage === 'craft' ? (
          <div className="craftStack">
            {renderCraftPieceSelector()}

            <section className="section">
              <div className="sectionHeader">
                <h2>Craft outfits</h2>
                <div className="outfitControls">
                  <label className="selectLabel">
                    Count
                    <select
                      className="select"
                      data-testid="outfit-count"
                      value={outfitCount}
                      onChange={(e) => setOutfitCount(Number(e.target.value))}
                      disabled={busy}
                    >
                      {[1, 2, 3, 4, 5].map((count) => (
                        <option value={count} key={count}>
                          {count}
                        </option>
                      ))}
                    </select>
                  </label>
                  <button className="button primary" onClick={onGenerateOutfits} disabled={busy || selectedCount === 0}>
                    {busyAction === 'generate' ? 'Generating...' : `Generate ${outfitCount} outfit${outfitCount === 1 ? '' : 's'}`}
                  </button>
                </div>
              </div>

              {outfits?.outfits?.length ? (
                <div className="outfitGrid">{outfits.outfits.map((outfit, idx) => renderOutfitCard(outfit, idx, false))}</div>
              ) : (
                <div className="empty">Generate outfits after adding a few pieces.</div>
              )}
            </section>
          </div>
        ) : null}
      </main>

      {pendingDeleteItem ? (
        <div className="modalScrim" role="presentation">
          <div className="confirmModal" role="dialog" aria-modal="true" aria-labelledby="delete-piece-title">
            <h3 className="modalTitle" id="delete-piece-title">
              Are you sure you want to remove the piece from the closet?
            </h3>
            <div className="modalActions">
              <button className="button" type="button" onClick={() => setPendingDeleteItem(null)} disabled={busy}>
                Cancel
              </button>
              <button
                className="button primary"
                type="button"
                onClick={async () => {
                  const item = pendingDeleteItem
                  setPendingDeleteItem(null)
                  await onDeleteItem(item)
                }}
                disabled={busy}
              >
                Remove piece
              </button>
            </div>
          </div>
        </div>
      ) : null}

      {pendingDeleteOutfit ? (
        <div className="modalScrim" role="presentation">
          <div className="confirmModal" role="dialog" aria-modal="true" aria-labelledby="delete-outfit-title">
            <h3 className="modalTitle" id="delete-outfit-title">
              Are you sure you want to remove this outfit from My closet?
            </h3>
            <div className="modalActions">
              <button className="button" type="button" onClick={() => setPendingDeleteOutfit(null)} disabled={busy}>
                Cancel
              </button>
              <button
                className="button primary"
                type="button"
                onClick={async () => {
                  const outfit = pendingDeleteOutfit
                  setPendingDeleteOutfit(null)
                  await onDeleteSavedOutfit(outfit)
                }}
                disabled={busy}
              >
                Remove outfit
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  )
}

export default App
