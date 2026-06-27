import type { App, Directive } from 'vue'

interface LazyLoadOptions {
  loading?: string
  error?: string
  threshold?: number
}

const defaultOptions: LazyLoadOptions = {
  loading: 'data:image/svg+xml,%3Csvg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 40 40"%3E%3Ccircle cx="20" cy="20" r="18" fill="%23f0f2f5"/%3E%3C/svg%3E',
  error: 'data:image/svg+xml,%3Csvg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 40 40"%3E%3Ccircle cx="20" cy="20" r="18" fill="%23fef0f0"/%3E%3Ctext x="20" y="23" text-anchor="middle" fill="%23f56c6c" font-size="14"%3E!%3C/text%3E%3C/svg%3E',
  threshold: 0.1
}

function createLazyLoadDirective(options: LazyLoadOptions = {}): Directive {
  const { loading, error, threshold } = { ...defaultOptions, ...options }

  const observerMap = new WeakMap<HTMLElement, IntersectionObserver>()

  return {
    mounted(el: HTMLImageElement, binding) {
      const src = binding.value

      if (!src) return

      el.setAttribute('data-src', src)
      el.setAttribute('src', loading!)
      el.classList.add('lazy-image')

      const observer = new IntersectionObserver(
        (entries) => {
          entries.forEach((entry) => {
            if (entry.isIntersecting) {
              const img = entry.target as HTMLImageElement
              const dataSrc = img.getAttribute('data-src')

              if (dataSrc) {
                const tempImg = new Image()
                tempImg.src = dataSrc

                tempImg.onload = () => {
                  img.src = dataSrc
                  img.classList.add('lazy-loaded')
                  img.classList.remove('lazy-loading')
                }

                tempImg.onerror = () => {
                  img.src = error!
                  img.classList.add('lazy-error')
                  img.classList.remove('lazy-loading')
                }
              }

              observer.unobserve(img)
            }
          })
        },
        { threshold }
      )

      observer.observe(el)
      observerMap.set(el, observer)
    },

    updated(el: HTMLImageElement, binding) {
      const src = binding.value
      const oldSrc = binding.oldValue

      if (src && src !== oldSrc) {
        el.setAttribute('data-src', src)
        el.classList.remove('lazy-loaded', 'lazy-error')
        el.src = loading!

        const observer = observerMap.get(el)
        if (observer) {
          observer.unobserve(el)
          observer.observe(el)
        }
      }
    },

    unmounted(el) {
      const observer = observerMap.get(el)
      if (observer) {
        observer.unobserve(el)
        observer.disconnect()
        observerMap.delete(el)
      }
    }
  }
}

export default {
  install(app: App, options?: LazyLoadOptions) {
    const directive = createLazyLoadDirective(options)
    app.directive('lazy', directive)
  }
}

export { createLazyLoadDirective }
