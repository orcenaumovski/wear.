import { useEffect, useMemo, useState } from 'react'
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

type OutfitResponse = {
  outfits: Array<{
    name: string
    reasoning?: string | null
    items?: Array<{ itemId: number; role: string }>
  }>
}

const API_ORIGIN = 'http://127.0.0.1:8080'
const API_BASE = `${API_ORIGIN}/api`
const MAX_IMAGE_DIMENSION = 1400

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

function App() {
  const [items, setItems] = useState<Item[]>([])
  const [files, setFiles] = useState<File[]>([])
  const [previewUrls, setPreviewUrls] = useState<string[]>([])
  const [busyAction, setBusyAction] = useState<string | null>(null)
  const [editingRole, setEditingRole] = useState<string | null>(null)
  const [pendingDeleteItem, setPendingDeleteItem] = useState<Item | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [outfits, setOutfits] = useState<OutfitResponse | null>(null)
  const [outfitCount, setOutfitCount] = useState(3)

  const busy = busyAction !== null
  const itemsById = useMemo(() => new Map(items.map((item) => [item.id, item])), [items])
  const closetGroups = useMemo(() => groupItemsByRole(items), [items])

  function imageSrcFor(item: Item) {
    if (item.imageUrl?.startsWith('http')) return item.imageUrl
    if (item.imageUrl?.startsWith('/')) return `${API_ORIGIN}${item.imageUrl}`
    return `${API_BASE}/items/${item.id}/image`
  }

  async function refresh() {
    const res = await fetch(`${API_BASE}/items`)
    if (!res.ok) throw new Error(`Failed to load items: ${res.status}`)
    const data = (await res.json()) as Item[]
    setItems(data)
  }

  useEffect(() => {
    refresh().catch((e) => setError(String(e)))
  }, [])

  useEffect(() => {
    const nextPreviewUrls = files.map((file) => URL.createObjectURL(file))
    setPreviewUrls(nextPreviewUrls)

    return () => {
      nextPreviewUrls.forEach((url) => URL.revokeObjectURL(url))
    }
  }, [files])

  async function onUpload(e: React.FormEvent) {
    e.preventDefault()
    if (files.length === 0) return

    setBusyAction('upload')
    setError(null)
    try {
      const form = new FormData()
      const convertedFiles = await Promise.all(files.map(fileForUpload))
      for (const file of convertedFiles) form.append('images', file)

      const res = await fetch(`${API_BASE}/items`, { method: 'POST', body: form })
      if (!res.ok) {
        const text = await res.text().catch(() => '')
        throw new Error(`Upload failed: ${res.status} ${text}`)
      }

      setFiles([])
      setOutfits(null)
      await refresh()
    } catch (e) {
      setError(String(e))
    } finally {
      setBusyAction(null)
    }
  }

  async function onAnalyzeItem(item: Item) {
    setBusyAction(`analyze-${item.id}`)
    setError(null)
    try {
      const form = new FormData()
      try {
        const imageRes = await fetch(imageSrcFor(item))
        if (imageRes.ok) {
          const blob = await imageRes.blob()
          form.append('image', await imageBlobToJpegFile(blob, `item-${item.id}.jpg`))
        }
      } catch {
        // Fall back to server-side analysis of the stored image if browser conversion fails.
      }

      const res = await fetch(`${API_BASE}/items/${item.id}/analyze`, {
        method: 'POST',
        body: form.has('image') ? form : undefined,
      })
      if (!res.ok) {
        const text = await res.text().catch(() => '')
        throw new Error(`Analyze failed: ${res.status} ${text}`)
      }

      const updated = (await res.json()) as Item
      setItems((current) => current.map((item) => (item.id === updated.id ? updated : item)))
      if (updated.analysisError) setError(`Analyze failed: ${updated.analysisError}`)
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
    try {
      const res = await fetch(`${API_BASE}/outfits/generate?count=${outfitCount}`, { method: 'POST' })
      if (!res.ok) {
        const text = await res.text().catch(() => '')
        throw new Error(`Generate failed: ${res.status} ${text}`)
      }

      const data = (await res.json()) as OutfitResponse
      setOutfits(data)
    } catch (e) {
      setError(String(e))
    } finally {
      setBusyAction(null)
    }
  }

  async function onDeleteItem(item: Item) {
    setBusyAction(`delete-${item.id}`)
    setError(null)
    try {
      const res = await fetch(`${API_BASE}/items/${item.id}`, { method: 'DELETE' })
      if (!res.ok) {
        const text = await res.text().catch(() => '')
        throw new Error(`Delete failed: ${res.status} ${text}`)
      }

      setItems((current) => current.filter((currentItem) => currentItem.id !== item.id))
      setOutfits(null)
    } catch (e) {
      setError(String(e))
    } finally {
      setBusyAction(null)
    }
  }

  return (
    <div className="page">
      <header className="header">
        <div className="titleBlock">
          <div className="title">wear.</div>
        </div>
      </header>

      <section className="section">
        <div className="sectionHeader">
          <h2>Add item</h2>
        </div>
        <form className="form" onSubmit={onUpload}>
          <div className="label">
            <input
              className="fileInput"
              id="closet-upload"
              type="file"
              accept="image/*"
              multiple
              onChange={(e) => setFiles(Array.from(e.target.files ?? []))}
            />
            <label className="button browseButton" htmlFor="closet-upload">
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
        {error ? <div className="error">{error}</div> : null}
      </section>

      <section className="section">
        <div className="sectionHeader">
          <h2>Closet</h2>
          <span className="count">{items.length} items</span>
        </div>
        {items.length === 0 ? (
          <div className="empty">No items yet.</div>
        ) : (
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
        )}
      </section>

      <section className="section">
        <div className="sectionHeader">
          <h2>Outfits</h2>
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
            <button className="button primary" onClick={onGenerateOutfits} disabled={busy || items.length === 0}>
              {busyAction === 'generate' ? 'Generating...' : `Generate ${outfitCount} outfit${outfitCount === 1 ? '' : 's'}`}
            </button>
          </div>
        </div>

        {outfits?.outfits?.length ? (
          <div className="outfitGrid">
            {outfits.outfits.map((outfit, idx) => {
              const outfitItems = Array.isArray(outfit.items) ? outfit.items : []

              return (
                <article className="outfitCard" data-testid="outfit-card" key={`${outfit.name}-${idx}`}>
                  <div className="outfitHeader">
                    <div>
                      <div className="outfitIndex">Outfit {idx + 1}</div>
                      <div className="outfitName">{outfit.name}</div>
                    </div>
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
                    <div className="empty compactEmpty">No pieces returned for this outfit.</div>
                  )}

                </article>
              )
            })}
          </div>
        ) : (
          <div className="empty">Generate outfits after uploading a few closet items.</div>
        )}
      </section>

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
    </div>
  )
}

export default App
