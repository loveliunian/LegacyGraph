import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import BaseTable from '@/components/common/BaseTable.vue'

const elementStubs = {
  'el-table': {
    template: '<div class="el-table"><slot /></div>',
    methods: {
      clearSelection: vi.fn(),
      toggleRowSelection: vi.fn(),
      setCurrentRow: vi.fn(),
      sort: vi.fn(),
    }
  },
  'el-table-column': { template: '<div></div>' },
  'el-pagination': {
    template: `
      <div class="el-pagination">
        <button class="prev" @click="handlePrev">上一页</button>
        <button class="next" @click="handleNext">下一页</button>
        <select class="size-select" @change="handleSizeChange">
          <option value="10">10条/页</option>
          <option value="20">20条/页</option>
          <option value="50">50条/页</option>
        </select>
      </div>
    `,
    props: ['currentPage', 'pageSize', 'total', 'pageSizes'],
    emits: ['update:currentPage', 'update:pageSize', 'size-change', 'current-change'],
    methods: {
      handlePrev() {
        if (this.currentPage > 1) {
          this.$emit('update:currentPage', this.currentPage - 1)
          this.$emit('current-change', this.currentPage - 1)
        }
      },
      handleNext() {
        const maxPage = Math.ceil(this.total / this.pageSize)
        if (this.currentPage < maxPage) {
          this.$emit('update:currentPage', this.currentPage + 1)
          this.$emit('current-change', this.currentPage + 1)
        }
      },
      handleSizeChange(e: Event) {
        const size = Number((e.target as HTMLSelectElement).value)
        this.$emit('update:pageSize', size)
        this.$emit('size-change', size)
      }
    }
  },
}

describe('BaseTable 组件', () => {
  let wrapper: VueWrapper<any>

  beforeEach(() => {
    setActivePinia(createPinia())
  })

  const createWrapper = (props = {}) => {
    return mount(BaseTable, {
      props: {
        data: [],
        ...props
      },
      slots: {
        default: '<el-table-column prop="name" label="名称" />'
      },
      global: {
        stubs: elementStubs
      }
    })
  }

  it('应该正确渲染表格容器', () => {
    const wrapper = createWrapper()
    expect(wrapper.find('.base-table').exists()).toBe(true)
  })

  it('分页开启时应该渲染分页组件', () => {
    const wrapper = createWrapper({ pagination: true })
    expect(wrapper.find('.base-table__pagination').exists()).toBe(true)
  })

  it('loading 状态应该显示加载效果', () => {
    const wrapper = createWrapper({ loading: true })
    expect(wrapper.exists()).toBe(true)
  })

  it('分页关闭时不应该渲染分页', () => {
    const wrapper = createWrapper({ pagination: false })
    expect(wrapper.find('.base-table__pagination').exists()).toBe(false)
  })

  describe('分页交互', () => {
    it('默认页码应为1', () => {
      wrapper = createWrapper({ pagination: true, total: 100 })
      const vm = wrapper.vm as any
      expect(vm.currentPage).toBe(1)
    })

    it('默认页大小应为20', () => {
      wrapper = createWrapper({ pagination: true, total: 100 })
      const vm = wrapper.vm as any
      expect(vm.pageSize).toBe(20)
    })

    it('切换页码时应触发update:page事件', async () => {
      wrapper = createWrapper({ pagination: true, total: 100, remotePagination: true })
      const vm = wrapper.vm as any

      vm.handleCurrentChange(3)
      await nextTick()

      expect(vm.currentPage).toBe(3)
      expect(wrapper.emitted('update:page')).toBeTruthy()
      expect(wrapper.emitted('update:page')?.[0]).toEqual([3])
    })

    it('切换页大小时应触发update:pageSize事件', async () => {
      wrapper = createWrapper({ pagination: true, total: 100, remotePagination: true })
      const vm = wrapper.vm as any

      vm.handleSizeChange(50)
      await nextTick()

      expect(vm.pageSize).toBe(50)
      expect(wrapper.emitted('update:pageSize')).toBeTruthy()
      expect(wrapper.emitted('update:pageSize')?.[0]).toEqual([50])
    })

    it('props.page 变化时应同步更新 currentPage', async () => {
      wrapper = createWrapper({ pagination: true, total: 100, page: 1 })
      const vm = wrapper.vm as any

      expect(vm.currentPage).toBe(1)

      await wrapper.setProps({ page: 3 })
      await nextTick()

      expect(vm.currentPage).toBe(3)
    })

    it('props.pageSize 变化时应同步更新 pageSize', async () => {
      wrapper = createWrapper({ pagination: true, total: 100, pageSize: 20 })
      const vm = wrapper.vm as any

      expect(vm.pageSize).toBe(20)

      await wrapper.setProps({ pageSize: 50 })
      await nextTick()

      expect(vm.pageSize).toBe(50)
    })
  })

  describe('本地分页（remotePagination=false）', () => {
    const mockData = Array.from({ length: 50 }, (_, i) => ({
      id: i + 1,
      name: `项目${i + 1}`
    }))

    it('第一页应显示前pageSize条数据', () => {
      wrapper = createWrapper({
        data: mockData,
        pagination: true,
        remotePagination: false,
        pageSize: 10
      })
      const vm = wrapper.vm as any

      expect(vm.tableData.length).toBe(10)
      expect(vm.tableData[0].id).toBe(1)
      expect(vm.tableData[9].id).toBe(10)
    })

    it('第二页应显示第11-20条数据', () => {
      wrapper = createWrapper({
        data: mockData,
        pagination: true,
        remotePagination: false,
        pageSize: 10,
        page: 2
      })
      const vm = wrapper.vm as any

      expect(vm.tableData.length).toBe(10)
      expect(vm.tableData[0].id).toBe(11)
      expect(vm.tableData[9].id).toBe(20)
    })

    it('最后一页应显示剩余数据', () => {
      wrapper = createWrapper({
        data: mockData,
        pagination: true,
        remotePagination: false,
        pageSize: 10,
        page: 5
      })
      const vm = wrapper.vm as any

      expect(vm.tableData.length).toBe(10)
      expect(vm.tableData[0].id).toBe(41)
      expect(vm.tableData[9].id).toBe(50)
    })

    it('页大小变化时应重新计算显示数据', async () => {
      wrapper = createWrapper({
        data: mockData,
        pagination: true,
        remotePagination: false,
        pageSize: 10,
        page: 1
      })
      const vm = wrapper.vm as any

      expect(vm.tableData.length).toBe(10)

      vm.handleSizeChange(25)
      await nextTick()

      expect(vm.tableData.length).toBe(25)
      expect(vm.tableData[0].id).toBe(1)
      expect(vm.tableData[24].id).toBe(25)
    })
  })

  describe('远程分页（remotePagination=true）', () => {
    const mockData = Array.from({ length: 10 }, (_, i) => ({
      id: i + 1,
      name: `项目${i + 1}`
    }))

    it('远程分页应直接显示传入的data', () => {
      wrapper = createWrapper({
        data: mockData,
        pagination: true,
        remotePagination: true,
        pageSize: 20,
        total: 100
      })
      const vm = wrapper.vm as any

      expect(vm.tableData.length).toBe(10)
      expect(vm.tableData).toEqual(mockData)
    })
  })

  describe('排序和选择事件', () => {
    it('排序变化时应触发sort-change事件', () => {
      wrapper = createWrapper({ pagination: true })
      const vm = wrapper.vm as any

      vm.handleSortChange({ prop: 'name', order: 'ascending' })

      expect(wrapper.emitted('sort-change')).toBeTruthy()
      expect(wrapper.emitted('sort-change')?.[0]).toEqual([
        { prop: 'name', order: 'ascending' }
      ])
    })

    it('选择变化时应触发selection-change事件', () => {
      wrapper = createWrapper({ pagination: true })
      const vm = wrapper.vm as any
      const selectedRows = [{ id: 1, name: '项目1' }]

      vm.handleSelectionChange(selectedRows)

      expect(wrapper.emitted('selection-change')).toBeTruthy()
      expect(wrapper.emitted('selection-change')?.[0]).toEqual([selectedRows])
    })
  })

  describe('暴露的方法', () => {
    it('应暴露clearSelection方法', () => {
      wrapper = createWrapper({ pagination: true })
      expect(typeof (wrapper.vm as any).clearSelection).toBe('function')
    })

    it('应暴露toggleRowSelection方法', () => {
      wrapper = createWrapper({ pagination: true })
      expect(typeof (wrapper.vm as any).toggleRowSelection).toBe('function')
    })

    it('应暴露setCurrentRow方法', () => {
      wrapper = createWrapper({ pagination: true })
      expect(typeof (wrapper.vm as any).setCurrentRow).toBe('function')
    })

    it('应暴露sort方法', () => {
      wrapper = createWrapper({ pagination: true })
      expect(typeof (wrapper.vm as any).sort).toBe('function')
    })
  })
})
