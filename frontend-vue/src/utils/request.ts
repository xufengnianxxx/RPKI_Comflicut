import axios, { type AxiosError, type InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'

/**
 * 全局 Axios 实例：baseURL 为空，请求使用相对路径 /api/...，由 Vite 代理转发到后端。
 */
const request = axios.create({
  baseURL: '',
  timeout: 120_000,
  headers: { 'Content-Type': 'application/json' },
})

request.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    // 对需要长时间运行的检测接口可在外层单独传 signal / 不显示全局提示
    return config
  },
  (err) => Promise.reject(err),
)

request.interceptors.response.use(
  (res) => res,
  (err: AxiosError<{ message?: string }>) => {
    const msg =
      err.response?.data?.message ||
      err.message ||
      (err.response?.status === 404 ? '资源不存在' : '网络错误，请检查后端与代理配置')
    ElMessage.error(msg)
    return Promise.reject(err)
  },
)

export default request
