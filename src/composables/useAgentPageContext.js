import { readonly, shallowRef } from 'vue'

// 页面上下文是全局桌宠与当前路由之间的轻量桥梁。
// 这里只保存文章 ID、标题和编辑器回调，不把正文复制到浏览器持久存储。
const currentContext = shallowRef({ kind: 'general' })

/** 注册当前页面能力，新路由会整体覆盖旧路由的上下文。 */
export function setAgentPageContext(context) {
  currentContext.value = context && typeof context === 'object'
    ? { ...context }
    : { kind: 'general' }
}

/** 离开页面时清除闭包回调，避免桌宠误操作已销毁的编辑器实例。 */
export function clearAgentPageContext() {
  currentContext.value = { kind: 'general' }
}

/** 组件只读订阅当前上下文，更新权始终留在页面级组件。 */
export function useAgentPageContext() {
  return readonly(currentContext)
}
