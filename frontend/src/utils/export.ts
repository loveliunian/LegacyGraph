import { ElMessage, ElLoading } from 'element-plus'
import type { LoadingInstance } from 'element-plus/es/components/loading/src/loading'

export type ExportFormat = 'json' | 'csv' | 'excel' | 'txt' | 'html'

export interface ExportColumn {
  key: string
  title: string
  width?: number
  formatter?: (value: any, row: any, index: number) => string
}

export interface ExportOptions {
  filename?: string
  format?: ExportFormat
  sheetName?: string
  bom?: boolean
  separator?: string
  encoding?: string
}

const defaultOptions: Required<ExportOptions> = {
  filename: `export_${new Date().toISOString().slice(0, 10)}`,
  format: 'json',
  sheetName: 'Sheet1',
  bom: true,
  separator: ',',
  encoding: 'utf-8'
}

let loadingInstance: LoadingInstance | null = null

function showLoading(text = '导出中...') {
  loadingInstance = ElLoading.service({
    lock: true,
    text,
    background: 'rgba(255, 255, 255, 0.7)'
  })
}

function hideLoading() {
  if (loadingInstance) {
    loadingInstance.close()
    loadingInstance = null
  }
}

function downloadFile(content: string | Blob, filename: string, mimeType: string) {
  const blob = content instanceof Blob
    ? content
    : new Blob([content], { type: `${mimeType};charset=utf-8` })

  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

export function toJSON(data: any[], options: ExportOptions = {}): string {
  const { filename } = { ...defaultOptions, ...options }
  const content = JSON.stringify(data, null, 2)
  downloadFile(content, `${filename}.json`, 'application/json')
  return content
}

export function toCSV(data: any[], columns: ExportColumn[], options: ExportOptions = {}): string {
  const { filename, bom, separator } = { ...defaultOptions, ...options }

  const headers = columns.map(col => col.title).join(separator)
  const rows = data.map((row, index) => {
    return columns.map(col => {
      const value = row[col.key]
      const formatted = col.formatter ? col.formatter(value, row, index) : value
      const cellValue = String(formatted ?? '')
      if (cellValue.includes(separator) || cellValue.includes('"') || cellValue.includes('\n')) {
        return `"${cellValue.replace(/"/g, '""')}"`
      }
      return cellValue
    }).join(separator)
  })

  const csvContent = [headers, ...rows].join('\n')
  const bomContent = bom ? '\uFEFF' + csvContent : csvContent
  downloadFile(bomContent, `${filename}.csv`, 'text/csv')
  return csvContent
}

export function toExcel(data: any[], columns: ExportColumn[], options: ExportOptions = {}): void {
  const { filename, sheetName } = { ...defaultOptions, ...options }

  const headerRow = columns.map(col =>
    `<Cell><Data ss:Type="String">${col.title}</Data></Cell>`
  ).join('')

  const dataRows = data.map((row, index) =>
    `<Row>${columns.map(col => {
      const value = col.formatter ? col.formatter(row[col.key], row, index) : row[col.key]
      const cellValue = String(value ?? '')
      return `<Cell><Data ss:Type="String">${cellValue}</Data></Cell>`
    }).join('')}</Row>`
  ).join('')

  const worksheetXml = `
<?xml version="1.0" encoding="utf-8"?>
<?mso-application progid="Excel.Sheet"?>
<Workbook xmlns="urn:schemas-microsoft-com:office:spreadsheet"
  xmlns:ss="urn:schemas-microsoft-com:office:spreadsheet">
  <Worksheet ss:Name="${sheetName}">
    <Table>
      <Row>${headerRow}</Row>
      ${dataRows}
    </Table>
  </Worksheet>
</Workbook>`

  downloadFile(worksheetXml, `${filename}.xls`, 'application/vnd.ms-excel')
}

export function toTxt(data: any[], columns: ExportColumn[], options: ExportOptions = {}): void {
  const { filename, bom } = { ...defaultOptions, ...options }

  const colWidths = columns.map(col =>
    Math.max(col.width ?? 15, col.title.length, ...data.map(row => String(row[col.key] ?? '').length))
  )

  const separatorRow = '+' + colWidths.map(w => '-'.repeat(w + 2)).join('+') + '+'
  const headerRow = '|' + columns.map((col, i) => ` ${col.title.padEnd(colWidths[i])} `).join('|') + '|'

  const dataRows = data.map((row, index) =>
    '|' + columns.map((col, i) => {
      const value = col.formatter ? col.formatter(row[col.key], row, index) : row[col.key]
      const cellValue = String(value ?? '').padEnd(colWidths[i])
      return ` ${cellValue} `
    }).join('|') + '|'
  )

  const content = [separatorRow, headerRow, separatorRow, ...dataRows, separatorRow].join('\n')
  const bomContent = bom ? '\uFEFF' + content : content
  downloadFile(bomContent, `${filename}.txt`, 'text/plain')
}

export function toHtml(data: any[], columns: ExportColumn[], options: ExportOptions = {}): void {
  const { filename } = { ...defaultOptions, ...options }

  const tableHtml = `
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>${filename}</title>
  <style>
    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; padding: 20px; }
    h1 { color: #333; border-bottom: 2px solid #409eff; padding-bottom: 10px; }
    table { width: 100%; border-collapse: collapse; margin-top: 20px; }
    th, td { border: 1px solid #ddd; padding: 12px; text-align: left; }
    th { background: #409eff; color: white; font-weight: 600; }
    tr:nth-child(even) { background: #f5f7fa; }
    tr:hover { background: #ecf5ff; }
    .export-info { color: #909399; font-size: 14px; margin-top: 20px; }
  </style>
</head>
<body>
  <h1>数据导出</h1>
  <table>
    <thead>
      <tr>${columns.map(col => `<th>${col.title}</th>`).join('')}</tr>
    </thead>
    <tbody>
      ${data.map((row, index) => `
        <tr>${columns.map(col => {
          const value = col.formatter ? col.formatter(row[col.key], row, index) : row[col.key]
          return `<td>${value ?? ''}</td>`
        }).join('')}</tr>
      `).join('')}
    </tbody>
  </table>
  <div class="export-info">
    导出时间: ${new Date().toLocaleString('zh-CN')}<br>
    数据条数: ${data.length} 条
  </div>
</body>
</html>`

  downloadFile(tableHtml, `${filename}.html`, 'text/html')
}

export async function exportData(
  data: any[],
  columns: ExportColumn[],
  options: ExportOptions = {}
): Promise<void> {
  const { format } = { ...defaultOptions, ...options }

  showLoading('正在导出...')

  try {
    await new Promise(resolve => setTimeout(resolve, 300))

    switch (format) {
      case 'json':
        toJSON(data, options)
        break
      case 'csv':
        toCSV(data, columns, options)
        break
      case 'excel':
        toExcel(data, columns, options)
        break
      case 'txt':
        toTxt(data, columns, options)
        break
      case 'html':
        toHtml(data, columns, options)
        break
      default:
        throw new Error(`不支持的导出格式: ${format}`)
    }

    ElMessage.success('导出成功！')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '导出失败')
    throw error
  } finally {
    hideLoading()
  }
}

export function useExport() {
  return {
    exportData,
    toJSON,
    toCSV,
    toExcel,
    toTxt,
    toHtml
  }
}
