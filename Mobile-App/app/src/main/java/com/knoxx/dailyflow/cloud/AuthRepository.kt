package com.knoxx.dailyflow.cloud

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.providers.builtin.Email
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Thin wrapper over Supabase Auth — email/password only for now (matches
 *  what's actually needed: this is a personal-memory app, not a consumer
 *  product with social login pressure). The session itself is what every
 *  RLS policy in the `memories` table keys off of (`auth.uid()`), so signing
 *  in isn't optional once cloud sync exists — it's what makes "only you can
 *  see your own memories" true. */
object AuthRepository {

    private val auth get() = SupabaseClientProvider.client.auth

    val sessionStatus: Flow<SessionStatus> get() = auth.sessionStatus

    val isSignedIn: Flow<Boolean> get() = sessionStatus.map { it is SessionStatus.Authenticated }

    val currentUserId: String? get() = auth.currentUserOrNull()?.id

    suspend fun signUp(email: String, password: String) {
        auth.signUpWith(Email) {
            this.email = email
            this.password = password
        }
    }

    suspend fun signIn(email: String, password: String) {
        auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    suspend fun signOut() {
        auth.signOut()
    }
}
