import { expect, test } from '@playwright/test'

const imageBytes = Buffer.from(
  '/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////2wBDAf//////////////////////////////////////////////////////////////////////////////////////wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAX/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIQAxAAAAH/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oACAEBAAEFAqf/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oACAEDAQE/ASP/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oACAECAQE/ASP/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oACAEBAAY/Al//xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oACAEBAAE/IV//2gAMAwEAAgADAAAAEP/EABQRAQAAAAAAAAAAAAAAAAAAABD/2gAIAQMBAT8QH//EABQRAQAAAAAAAAAAAAAAAAAAABD/2gAIAQIBAT8QH//EABQQAQAAAAAAAAAAAAAAAAAAABD/2gAIAQEAAT8QH//Z',
  'base64',
)

const initialCloset = [
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

test.beforeEach(async ({ page }) => {
  const closet = structuredClone(initialCloset)

  await page.route('http://127.0.0.1:8080/api/items', async (route) => {
    await route.fulfill({ json: closet })
  })

  await page.route('http://127.0.0.1:8080/api/items/*', async (route) => {
    if (route.request().method() !== 'DELETE') {
      await route.fallback()
      return
    }

    const match = route.request().url().match(/\/api\/items\/(\d+)$/)
    const id = Number(match?.[1])
    const index = closet.findIndex((item) => item.id === id)
    if (index >= 0) {
      closet.splice(index, 1)
    }

    await route.fulfill({ status: 204 })
  })

  await page.route('http://127.0.0.1:8080/api/items/*/image', async (route) => {
    await route.fulfill({
      body: imageBytes,
      contentType: 'image/jpeg',
    })
  })

  await page.route('http://127.0.0.1:8080/api/outfits/generate**', async (route) => {
    const url = new URL(route.request().url())
    const count = Number(url.searchParams.get('count') ?? '3')
    await route.fulfill({
      json: {
        outfits: Array.from({ length: count }, (_, index) => ({
          name: `Look ${index + 1}`,
          reasoning: 'Uses selected closet pieces.',
          items: [
            { itemId: 1, role: 'jacket' },
            { itemId: 2, role: 'shirt' },
          ],
        })),
      },
    })
  })
})

test('groups closet photos by role and hides item metadata', async ({ page }) => {
  await page.goto('/')

  await expect(page.getByText('wear.')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Refresh' })).toHaveCount(0)
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

test('lets the user edit a role section and delete a piece', async ({ page }) => {
  await page.goto('/')

  const outerwearSection = page.locator('.roleSection', {
    has: page.getByRole('heading', { name: 'Outerwear' }),
  })

  await outerwearSection.getByRole('button', { name: 'Edit' }).click()
  await expect(outerwearSection.getByRole('button', { name: 'Remove piece' })).toHaveCount(1)
  await outerwearSection.getByRole('button', { name: 'Remove piece' }).click()
  await expect(page.getByText('Are you sure you want to remove the piece from the closet?')).toBeVisible()
  await page.getByRole('button', { name: 'Remove piece' }).last().click()

  await expect(page.getByRole('img', { name: 'Outerwear item' })).toHaveCount(0)
  await expect(page.getByText('2 items')).toBeVisible()
})

test('lets the user generate up to 5 outfits and shows the selected pieces', async ({ page }) => {
  await page.goto('/')

  await page.getByTestId('outfit-count').selectOption('5')
  await page.getByRole('button', { name: 'Generate 5 outfits' }).click()

  await expect(page.getByTestId('outfit-card')).toHaveCount(5)
  await expect(page.getByTestId('outfit-piece')).toHaveCount(10)
  await expect(page.getByTestId('outfit-piece').filter({ hasText: 'Outerwear' })).toHaveCount(5)
  await expect(page.getByTestId('outfit-piece').filter({ hasText: 'Unsorted' })).toHaveCount(5)
  await expect(page.getByText('Uses selected closet pieces.')).toHaveCount(0)
  await expect(page.getByTestId('outfit-card').filter({ hasText: 'Black jacket' })).toHaveCount(0)
  await expect(page.getByTestId('outfit-card').filter({ hasText: 'Item #2' })).toHaveCount(0)
})
