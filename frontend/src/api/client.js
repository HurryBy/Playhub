import axios from 'axios'

const client = axios.create({
  baseURL: '/',
  timeout: 90000,
  withCredentials: true,
})

function unwrapError(error) {
  if (error?.response?.data?.message) {
    return error.response.data.message
  }
  if (typeof error?.response?.data === 'string' && error.response.data) {
    return error.response.data
  }
  return error?.message || '请求失败'
}

export function isAbortError(error) {
  return axios.isCancel(error) || error?.name === 'CanceledError' || error?.code === 'ERR_CANCELED'
}

export async function getJson(url, config = {}) {
  try {
    const { data } = await client.get(url, config)
    return data
  } catch (error) {
    if (isAbortError(error)) {
      throw error
    }
    throw new Error(unwrapError(error))
  }
}

export async function postJson(url, body = {}, config = {}) {
  try {
    const { data } = await client.post(url, body, config)
    return data
  } catch (error) {
    if (isAbortError(error)) {
      throw error
    }
    throw new Error(unwrapError(error))
  }
}
