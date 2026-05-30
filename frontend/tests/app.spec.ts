import { expect, type Page, test } from '@playwright/test'

const imageBytes = Buffer.from(
  '/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////2wBDAf//////////////////////////////////////////////////////////////////////////////////////wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAX/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIQAxAAAAH/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oACAEBAAEFAqf/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oACAEDAQE/ASP/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oACAECAQE/ASP/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oACAEBAAY/Al//xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oACAEBAAE/IV//2gAMAwEAAgADAAAAEP/EABQRAQAAAAAAAAAAAAAAAAAAABD/2gAIAQMBAT8QH//EABQRAQAAAAAAAAAAAAAAAAAAABD/2gAIAQIBAT8QH//EABQQAQAAAAAAAAAAAAAAAAAAABD/2gAIAQEAAT8QH//Z',
  'base64',
)

const testSession = {
  token: 'test-token',
  user: { id: 1, username: 'sofia' },
}

const initialPieces = [
  {
    id: 1,
    name: 'Black jacket',
    category: 'outerwear',
    tagsJson: '["casual"]',
    colorsJson: '["black"]',
    imagePath: 'mock-1.jpg',
    imageUrl: '/api/items/1/image',
    createdAtEpochMs: 1777401214207,
    analysisError: null,
  },
  {
    id: 2,
    name: 'no image provided',
    category: 'unknown',
    tagsJson: '["error:no photo"]',
    colorsJson: '[]',
    imagePath: 'mock-2.jpg',
    imageUrl: '/api/items/2/image',
    createdAtEpochMs: 1777401214207,
    analysisError: null,
  },
  {
    id: 3,
    name: 'Silver ring',
    category: 'jewelry',
    tagsJson: '["formal"]',
    colorsJson: '["silver"]',
    imagePath: 'mock-3.jpg',
    imageUrl: '/api/items/3/image',
    createdAtEpochMs: 1777401214207,
    analysisError: null,
  },
]

async function signIn(page: Page) {
  await page.addInitScript((session) => {
    window.sessionStorage.setItem('wear.auth', JSON.stringify(session))
  }, testSession)
}

test.beforeEach(async ({ page }) => {
  const pieces = structuredClone(initialPieces)
  const savedOutfits: Array<{
    id: number
    name: string
    createdAtEpochMs: number
    items: Array<{ itemId: number; role: string }>
  }> = []

  await page.route('http://127.0.0.1:8080/api/auth/register', async (route) => {
    await route.fulfill({ json: testSession })
  })

  await page.route('http://127.0.0.1:8080/api/auth/login', async (route) => {
    await route.fulfill({ json: testSession })
  })

  await page.route('http://127.0.0.1:8080/api/items', async (route) => {
    await route.fulfill({ json: pieces })
  })

  await page.route('http://127.0.0.1:8080/api/items/*/image**', async (route) => {
    await route.fulfill({
      body: imageBytes,
      contentType: 'image/jpeg',
    })
  })

  await page.route('http://127.0.0.1:8080/api/items/*', async (route) => {
    if (route.request().method() !== 'DELETE') {
      await route.fallback()
      return
    }

    const match = route.request().url().match(/\/api\/items\/(\d+)$/)
    const id = Number(match?.[1])
    const index = pieces.findIndex((item) => item.id === id)
    if (index >= 0) {
      pieces.splice(index, 1)
    }

    await route.fulfill({ status: 204 })
  })

  await page.route('http://127.0.0.1:8080/api/outfits/generate**', async (route) => {
    const url = new URL(route.request().url())
    const count = Number(url.searchParams.get('count') ?? '3')
    const body = route.request().postDataJSON() as { itemIds?: number[] } | null
    const selectedIds = body?.itemIds?.length ? body.itemIds : pieces.map((item) => item.id)
    const selectedPieces = selectedIds.slice(0, 2)
    await route.fulfill({
      json: {
        outfits: Array.from({ length: count }, (_, index) => ({
          name: `Look ${index + 1}`,
          reasoning: 'Uses selected closet pieces.',
          items: selectedPieces.map((itemId) => ({
            itemId,
            role: pieces.find((item) => item.id === itemId)?.category ?? 'item',
          })),
        })),
      },
    })
  })

  await page.route('http://127.0.0.1:8080/api/outfits', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({ json: { outfits: savedOutfits } })
      return
    }

    if (route.request().method() === 'POST') {
      const body = route.request().postDataJSON() as { name: string; items: Array<{ itemId: number; role: string }> }
      const saved = {
        id: savedOutfits.length + 1,
        name: body.name,
        createdAtEpochMs: Date.now(),
        items: body.items,
      }
      savedOutfits.unshift(saved)
      await route.fulfill({ json: saved })
      return
    }

    await route.fallback()
  })

  await page.route('http://127.0.0.1:8080/api/outfits/*', async (route) => {
    if (route.request().method() !== 'DELETE') {
      await route.fallback()
      return
    }

    const match = route.request().url().match(/\/api\/outfits\/(\d+)$/)
    const id = Number(match?.[1])
    const index = savedOutfits.findIndex((outfit) => outfit.id === id)
    if (index >= 0) {
      savedOutfits.splice(index, 1)
    }

    await route.fulfill({ status: 204 })
  })
})

test('opens with an intro page and then shows login/register screens', async ({ page }) => {
  await page.goto('/')

  await expect(page.getByRole('heading', { name: 'wear.' })).toBeVisible()
  await expect(page.getByText('Turn single clothing-piece photos into a visual closet')).toBeVisible()
  await page.getByRole('button', { name: "Let's craft some outfits" }).click()

  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByRole('heading', { name: 'Log in' })).toBeVisible()
  await page.getByRole('button', { name: 'Create a new account' }).click()
  await expect(page).toHaveURL(/\/register$/)
  await expect(page.getByRole('heading', { name: 'Create account' })).toBeVisible()
})

test('lets the user log in and enter the crafting workspace', async ({ page }) => {
  await page.goto('/login')

  await page.getByLabel('Username').fill('sofia')
  await page.getByLabel('Password').fill('secret1')
  await expect(page.getByLabel('Stay logged in')).not.toBeChecked()
  await page.getByRole('button', { name: 'Log in' }).click()

  await expect(page).toHaveURL(/\/craft$/)
  await expect(page.getByRole('button', { name: 'Craft', exact: true })).toHaveClass(/active/)
  await expect(page.locator('.userBadge')).toHaveCount(0)
  await expect(page.getByText('Signed in as sofia.')).toHaveCount(0)
  expect(await page.evaluate(() => window.localStorage.getItem('wear.auth'))).toBeNull()
  expect(await page.evaluate(() => window.sessionStorage.getItem('wear.auth'))).toContain('test-token')
})

test('keeps the user logged in only when stay logged in is checked', async ({ page }) => {
  await page.goto('/login')

  await page.getByLabel('Username').fill('sofia')
  await page.getByLabel('Password').fill('secret1')
  await page.getByLabel('Stay logged in').check()
  await page.getByRole('button', { name: 'Log in' }).click()

  await expect(page).toHaveURL(/\/craft$/)
  expect(await page.evaluate(() => window.localStorage.getItem('wear.auth'))).toContain('test-token')
  expect(await page.evaluate(() => window.sessionStorage.getItem('wear.auth'))).toBeNull()
})

test('shows pieces by role in My pieces and hides item metadata', async ({ page }) => {
  await signIn(page)
  await page.goto('/my-pieces')

  await expect(page.getByRole('button', { name: 'My pieces' })).toHaveClass(/active/)
  await expect(page.getByText('Stroll through the closet')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Outerwear' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Unsorted' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Jewelry' })).toBeVisible()
  await expect(page.getByRole('img', { name: 'Outerwear item' })).toBeVisible()
  await expect(page.getByRole('img', { name: 'Unsorted item' })).toBeVisible()
  await expect(page.getByRole('img', { name: 'Jewelry item' })).toBeVisible()
  await expect(page.getByText('Black jacket')).toHaveCount(0)
  await expect(page.getByText('Silver ring')).toHaveCount(0)
  await expect(page.getByText('Needs category')).toHaveCount(0)
  await expect(page.getByText('error:no photo')).toHaveCount(0)
  await expect(page.getByText('Previous analysis did not read this image.')).toBeVisible()
})

test('lets the user edit a piece section and delete a piece', async ({ page }) => {
  await signIn(page)
  await page.goto('/my-pieces')

  const outerwearSection = page.locator('.roleSection', {
    has: page.getByRole('heading', { name: 'Outerwear' }),
  })

  await outerwearSection.getByRole('button', { name: 'Edit' }).click()
  await expect(outerwearSection.getByRole('button', { name: 'Remove piece' })).toHaveCount(1)
  await outerwearSection.getByRole('button', { name: 'Remove piece' }).click()
  await expect(page.getByText('Are you sure you want to remove the piece from the closet?')).toBeVisible()
  await page.getByRole('button', { name: 'Remove piece' }).last().click()

  await expect(page.getByRole('img', { name: 'Outerwear item' })).toHaveCount(0)
  await expect(page.getByText('2 pieces')).toBeVisible()
})

test('generates with selected pieces and saves an outfit to My closet', async ({ page }) => {
  await signIn(page)
  await page.goto('/craft')

  await expect(page.getByText('3 of 3 pieces selected')).toBeVisible()
  await page.getByRole('button', { name: 'Add new pieces' }).click()
  await expect(page.getByTestId('piece-selector')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Select pieces to craft an outfit with' })).toHaveCount(0)
  await page.locator('.selectorItem', { hasText: 'Jewelry' }).getByRole('checkbox').uncheck()
  await expect(page.getByText('2 of 3 pieces selected')).toBeVisible()

  await page.getByTestId('outfit-count').selectOption('5')
  await page.getByRole('button', { name: 'Generate 5 outfits' }).click()

  await expect(page.getByTestId('outfit-card')).toHaveCount(5)
  await expect(page.getByTestId('outfit-piece')).toHaveCount(10)
  await expect(page.getByText('Uses selected closet pieces.')).toHaveCount(0)
  const firstOutfit = page.getByTestId('outfit-card').first()
  await firstOutfit.getByRole('button', { name: 'Save outfit' }).click()
  await expect(firstOutfit.getByRole('button', { name: 'Saved!' })).toBeDisabled()
  await expect(page.getByText('Outfit saved to My closet.')).toHaveCount(0)

  await page.getByRole('button', { name: 'My closet' }).click()
  await expect(page.getByTestId('saved-outfit-card')).toHaveCount(1)
  await expect(page.getByText('Look 1')).toBeVisible()
  await page.getByRole('button', { name: 'Remove saved outfit' }).click()
  await expect(page.getByText('Are you sure you want to remove this outfit from My closet?')).toBeVisible()
  await page.getByRole('button', { name: 'Remove outfit' }).click()
  await expect(page.getByTestId('saved-outfit-card')).toHaveCount(0)
  await expect(page.getByText('No saved outfits yet.')).toBeVisible()
  await expect(page.getByText('Outfit removed from My closet.')).toHaveCount(0)
})
