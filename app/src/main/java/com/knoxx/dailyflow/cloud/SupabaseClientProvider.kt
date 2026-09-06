package com.knoxx.dailyflow.cloud

import com.knoxx.dailyflow.BuildConfig
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest

/**
 * Single shared client for Wick's Supabase project — backs both auth (sign
 * in/up) and the `memories` table sync. The anon key here is safe to ship
 * (Row Level Security on every table is what actually protects data, not
 * secrecy of this key); the service_role key that bypasses RLS never goes
 * anywhere near the app, only the backend Edge Function.
 */
object SupabaseClientProvider {
    val client by lazy {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY
        ) {
            install(Auth)
            install(Postgrest)
        }
    }
}
