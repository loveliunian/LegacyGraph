import { ElMessage } from 'element-plus'

export interface ExportColumn<T = any> {
  key: string
  title: string
  width?: number
  formatter?: (value: any, row: T, index: number) => string | number
}

export interface ExportOptions {
  filename: string
  format: 'excel' | 'csv' | 'pdf' | 'json'
  sheetName?: string
  title?: string
  pageSize?: 'a4' | 'letter'
  orientation?: 'portrait' | 'landscape'
}

function formatValue<T>(row: T, column: ExportColumn<T>, index: number): string | number {
  const value = (row as any)[column.key]
  if (column.formatter) {
    return column.formatter(value, row, index)
  }
  if (value === null || value === undefined) {
    return ''
  }
  if (typeof value === 'boolean') {
    return value ? '是' : '否'
  }
  return value
}

export async function exportData<T = any>(
  data: T[],
  columns: ExportColumn<T>[],
  options: ExportOptions
): Promise<void> {
  const { filename, format, sheetName = 'Sheet1', title, pageSize = 'a4', orientation = 'portrait' } = options

  try {
    switch (format) {
      case 'excel':
        exportExcel(data, columns, filename, sheetName, title)
        break
      case 'csv':
        exportCSV(data, columns, filename)
        break
      case 'pdf':
        exportPDF(data, columns, filename, title, pageSize, orientation)
        break
      case 'json':
        exportJSON(data, filename)
        break
      default:
        throw new Error(`不支持的导出格式: ${format}`)
    }
    ElMessage.success('导出成功')
  } catch (error) {
    console.error('导出失败:', error)
    ElMessage.error('导出失败，请重试')
    throw error
  }
}

export function exportExcel<T = any>(
  data: T[],
  columns: ExportColumn<T>[],
  filename: string,
  sheetName: string = 'Sheet1',
  title?: string
): void {
  const headers = columns.map(col => col.title)
  const rows = data.map((row, index) =>
    columns.map(col => String(formatValue(row, col, index)))
  )

  let html = `<html xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:x="urn:schemas-microsoft-com:office:excel" xmlns="http://www.w3.org/TR/REC-html40">
<head>
  <meta charset="UTF-8">
  <xml>
    <x:ExcelWorkbook>
      <x:ExcelWorksheets>
        <x:ExcelWorksheet>
          <x:Name>${sheetName}</x:Name>
          <x:WorksheetOptions>
            <x:DisplayGridlines/>
          </x:WorksheetOptions>
        </x:ExcelWorksheet>
      </x:ExcelWorksheets>
    </x:ExcelWorkbook>
  </xml>
  <style>
    td { border: 1px solid #ddd; padding: 4px; }
    th { background: #409eff; color: white; font-weight: bold; padding: 6px 4px; }
    .title { font-size: 18px; font-weight: bold; padding: 10px 0; }
  </style>
</head>
<body>`

  if (title) {
    html += `<div class="title">${title}</div>`
  }

  html += `<table>
    <thead>
      <tr>${headers.map(h => `<th>${h}</th>`).join('')}</tr>
    </thead>
    <tbody>`

  rows.forEach(row => {
    html += `<tr>${row.map(cell => `<td>${cell}</td>`).join('')}</tr>`
  })

  html += `</tbody></table></body></html>`

  const blob = new Blob([html], { type: 'application/vnd.ms-excel;charset=utf-8;' })
  downloadBlob(blob, `${filename}.xls`)
}

export function exportCSV<T = any>(
  data: T[],
  columns: ExportColumn<T>[],
  filename: string
): void {
  const headers = columns.map((col) => col.title).join(',')
  const rows = data.map((row, index) =>
    columns.map((col) => {
      const value = String(formatValue(row, col, index))
      return value.includes(',') || value.includes('"') || value.includes('\n')
        ? `"${value.replace(/"/g, '""')}"`
        : value
    }).join(',')
  )

  const csv = [headers, ...rows].join('\n')
  const BOM = '\uFEFF'
  const blob = new Blob([BOM + csv], { type: 'text/csv;charset=utf-8;' })

  downloadBlob(blob, `${filename}.csv`)
}

export function exportPDF<T = any>(
  data: T[],
  columns: ExportColumn<T>[],
  filename: string,
  title?: string,
  pageSize: 'a4' | 'letter' = 'a4',
  orientation: 'portrait' | 'landscape' = 'portrait'
): void {
  const printWindow = window.open('', '_blank')
  if (!printWindow) {
    ElMessage.error('无法打开打印窗口，请检查浏览器设置')
    return
  }

  const headers = columns.map(col => col.title)
  const rows = data.map((row, index) =>
    columns.map(col => String(formatValue(row, col, index)))
  )

  const pageWidth = orientation === 'landscape' ? '297mm' : '210mm'
  const pageHeight = orientation === 'landscape' ? '210mm' : '297mm'

  let html = `
<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <title>${title || filename}</title>
  <style>
    @page {
      size: ${pageSize} ${orientation};
      margin: 15mm;
    }
    body {
      font-family: Arial, sans-serif;
      font-size: 12px;
      line-height: 1.4;
      margin: 0;
      padding: 0;
    }
    .title {
      text-align: center;
      font-size: 18px;
      font-weight: bold;
      margin-bottom: 20px;
      color: #333;
    }
    table {
      width: 100%;
      border-collapse: collapse;
      margin-bottom: 20px;
    }
    th {
      background: #409eff;
      color: white;
      font-weight: bold;
      padding: 8px 6px;
      text-align: left;
      border: 1px solid #3a8ee6;
    }
    td {
      padding: 6px;
      border: 1px solid #ddd;
      word-break: break-all;
    }
    tr:nth-child(even) td {
      background: #f5f7fa;
    }
    .page-footer {
      position: fixed;
      bottom: 0;
      left: 0;
      right: 0;
      text-align: center;
      font-size: 10px;
      color: #999;
      padding: 10px;
    }
    @media print {
      body { -webkit-print-color-adjust: exact; print-color-adjust: exact; }
    }
  </style>
</head>
<body>`

  if (title) {
    html += `<div class="title">${title}</div>`
  }

  html += `<table>
    <thead>
      <tr>${headers.map(h => `<th>${h}</th>`).join('')}</tr>
    </thead>
    <tbody>`

  rows.forEach(row => {
    html += `<tr>${row.map(cell => `<td>${cell}</td>`).join('')}</tr>`
  })

  html += `</tbody></table>
    <div class="page-footer">
      导出时间: ${new Date().toLocaleString('zh-CN')}
    </div>
  </body>
  </html>`

  printWindow.document.write(html)
  printWindow.document.close()
  printWindow.focus()
  setTimeout(() => {
    printWindow.print()
  }, 250)
}

export function exportJSON<T = any>(data: T[], filename: string): void {
  const json = JSON.stringify(data, null, 2)
  const blob = new Blob([json], { type: 'application/json;charset=utf-8;' })
  downloadBlob(blob, `${filename}.json`)
}

function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

export function downloadFile(url: string, filename?: string): void {
  const link = document.createElement('a')
  link.href = url
  link.download = filename || ''
  link.target = '_blank'
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
}

export function previewFile(blob: Blob): void {
  const url = URL.createObjectURL(blob)
  window.open(url, '_blank')
}
