import type { Directive, DirectiveBinding } from 'vue'
import { ElMessage } from 'element-plus'

interface LazyLoadElement extends HTMLElement {
  _lazyObserver?: IntersectionObserver
  _lazyLoaded?: boolean
  _lazySrc?: string
  _lazyError?: boolean
}

export interface LazyLoadOptions {
  loading?: string
  error?: string
  threshold?: number
  rootMargin?: string
  preload?: boolean
}

const defaultOptions: Required<LazyLoadOptions> = {
  loading: 'data:image/svg+xml,%3Csvg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"%3E%3Crect width="100" height="100" fill="%23f5f7fa"/%3E%3Ctext x="50" y="50" text-anchor="middle" dy=".3em" font-size="14" fill="%23909399"%3E加载中...%3C/text%3E%3C/svg%3E',
  error: 'data:image/svg+xml,%3Csvg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"%3E%3Crect width="100" height="100" fill="%23fef0f0"/%3E%3Ctext x="50" y="50" text-anchor="middle" dy=".3em" font-size="14" fill="%23f56c6c"%3E加载失败%3C/text%3E%3C/svg%3E',
  threshold: 0.1,
  rootMargin: '50px',
  preload: true
}

let globalOptions: Required<LazyLoadOptions> = { ...defaultOptions }

export function setLazyLoadOptions(options: LazyLoadOptions) {
  globalOptions = { ...globalOptions, ...options }
}

export const vLazyLoad: Directive<LazyLoadElement, string> = {
  mounted(el, binding) {
    const src = binding.value
    if (!src) return

    const options = globalOptions
    el._lazySrc = src
    el._lazyLoaded = false
    el._lazyError = false

    if (el.tagName === 'IMG') {
      const imgEl = el as HTMLImageElement
      imgEl.src = options.loading
      imgEl.classList.add('lazy-loading')
      imgEl.style.transition = 'opacity 0.3s ease'
      imgEl.style.opacity = '0.7'
    } else {
      el.style.backgroundImage = `url(${options.loading})`
      el.style.backgroundSize = 'cover'
      el.style.backgroundPosition = 'center'
    }

    if (options.preload && isElementInViewport(el)) {
      loadImage(el, src, options)
      return
    }

    const observer = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting && !el._lazyLoaded && !el._lazyError) {
            loadImage(el, src, options)
            observer.unobserve(el)
          }
        })
      },
      {
        threshold: options.threshold,
        rootMargin: options.rootMargin
      }
    )

    observer.observe(el)
    el._lazyObserver = observer
  },

  updated(el, binding) {
    const newSrc = binding.value
    const oldSrc = binding.oldValue

    if (newSrc !== oldSrc && newSrc !== el._lazySrc) {
      el._lazyLoaded = false
      el._lazyError = false
      el._lazySrc = newSrc

      if (el._lazyObserver) {
        el._lazyObserver.disconnect()
      }

      if (newSrc) {
        const options = globalOptions

        if (el.tagName === 'IMG') {
          const imgEl = el as HTMLImageElement
          imgEl.src = options.loading
          imgEl.classList.add('lazy-loading')
          imgEl.style.opacity = '0.7'
        } else {
          el.style.backgroundImage = `url(${options.loading})`
        }

        if (isElementInViewport(el)) {
          loadImage(el, newSrc, options)
        } else {
          const observer = new IntersectionObserver(
            (entries) => {
              entries.forEach((entry) => {
                if (entry.isIntersecting && !el._lazyLoaded && !el._lazyError) {
                  loadImage(el, newSrc, options)
                  observer.unobserve(el)
                }
              })
            },
            {
              threshold: options.threshold,
              rootMargin: options.rootMargin
            }
          )

          observer.observe(el)
          el._lazyObserver = observer
        }
      }
    }
  },

  unmounted(el) {
    if (el._lazyObserver) {
      el._lazyObserver.disconnect()
      el._lazyObserver = undefined
    }
  }
}

function isElementInViewport(el: HTMLElement): boolean {
  const rect = el.getBoundingClientRect()
  return (
    rect.top < window.innerHeight + 100 &&
    rect.bottom > -100 &&
    rect.left < window.innerWidth + 100 &&
    rect.right > -100
  )
}

function loadImage(el: LazyLoadElement, src: string, options: Required<LazyLoadOptions>) {
  if (el._lazyLoaded || el._lazyError) return

  const tempImg = new Image()

  tempImg.onload = () => {
    if (el.tagName === 'IMG') {
      const imgEl = el as HTMLImageElement
      imgEl.src = src
      imgEl.classList.remove('lazy-loading')
      imgEl.classList.add('lazy-loaded')
      imgEl.style.opacity = '1'
    } else {
      el.style.backgroundImage = `url(${src})`
      el.classList.add('lazy-loaded')
    }
    el._lazyLoaded = true
  }

  tempImg.onerror = () => {
    el._lazyError = true
    if (el.tagName === 'IMG') {
      const imgEl = el as HTMLImageElement
      imgEl.src = options.error
      imgEl.classList.remove('lazy-loading')
      imgEl.classList.add('lazy-error')
      imgEl.style.opacity = '1'
    } else {
      el.style.backgroundImage = `url(${options.error})`
      el.classList.add('lazy-error')
    }
    console.warn(`图片加载失败: ${src}`)
  }

  tempImg.src = src
}

export function preloadImages(sources: string[]): Promise<void[]> {
  return Promise.all(
    sources.map(
      (src) =>
        new Promise<void>((resolve) => {
          const img = new Image()
          img.onload = () => resolve()
          img.onerror = () => resolve()
          img.src = src
        })
    )
  )
}

export function clearLazyLoadCache() {
  console.log('LazyLoad cache cleared')
}

export default vLazyLoad
