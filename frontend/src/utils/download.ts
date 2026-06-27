/**
 * 文件下载工具
 */

/**
 * 下载后端生成的文件（PDF/Excel）
 * @param url 下载地址
 * @param filename 建议文件名
 */
export async function downloadFile(url: string, filename?: string): Promise<void> {
  try {
    const response = await fetch(url, {
      method: 'GET',
      credentials: 'include'
    })
    if (!response.ok) {
      throw new Error(`Download failed: ${response.status}`)
    }
    const blob = await response.blob()
    downloadBlob(blob, filename || getFilenameFromResponse(response))
  } catch (error) {
    console.error('Download failed:', error)
    throw error
  }
}

/**
 * 下载 blob 对象
 */
export function downloadBlob(blob: Blob, filename: string): void {
  const url = window.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  window.URL.revokeObjectURL(url)
}

/**
 * 从响应头获取文件名
 */
function getFilenameFromResponse(response: Response): string {
  const disposition = response.headers.get('Content-Disposition')
  if (!disposition) {
    return 'download'
  }
  const filenameMatch = disposition.match(/filename\*?=['"]?(?:UTF-8'')?([^;\n"']+)/i)
  if (filenameMatch && filenameMatch.length > 1) {
    return decodeURIComponent(filenameMatch[1])
  }
  return 'download'
}

/**
 * 导出 JSON 数据到文件
 */
export function exportJson(data: any, filename: string): void {
  const json = JSON.stringify(data, null, 2)
  const blob = new Blob([json], { type: 'application/json' })
  downloadBlob(blob, filename.endsWith('.json') ? filename : `${filename}.json`)
}
