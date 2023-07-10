package one.njk.sao.data

sealed class ApiResult<T> (val data: T? = null){
    class Success<T>(data: T? = null): ApiResult<T>(data)
    class Failure<T>(data: T? = null): ApiResult<T>(data)
}
