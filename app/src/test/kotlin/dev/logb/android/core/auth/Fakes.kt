package dev.logb.android.core.auth

class FakeServerStore(private var record: ServerRecord? = null) : ServerStore {
    override suspend fun read(): ServerRecord? = record
    override suspend fun write(record: ServerRecord) { this.record = record }
    override suspend fun clear() { record = null }
}

class FakeTokenStore(private var token: String? = null) : TokenStore {
    override suspend fun read(): String? = token
    override suspend fun write(token: String) { this.token = token }
    override suspend fun clear() { token = null }
}
