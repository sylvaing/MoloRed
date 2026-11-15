package fr.sylvain.matv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * SFR login + password, encrypted with an AES-GCM key that never leaves the
 * Android Keystore. Used only to re-run the SFR login form when the session expires.
 */
object CredStore {
    private const val ALIAS = "matv_creds"
    private const val PREFS = "creds"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(enc: String): String {
        val all = Base64.decode(enc, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
        return String(c.doFinal(all, 12, all.size - 12))
    }

    fun save(ctx: Context, user: String, password: String) {
        ctx.getSharedPreferences(PREFS, 0).edit()
            .putString("u", encrypt(user)).putString("p", encrypt(password)).apply()
    }

    fun load(ctx: Context): Pair<String, String>? = try {
        val p = ctx.getSharedPreferences(PREFS, 0)
        val u = p.getString("u", null) ?: return null
        val pw = p.getString("p", null) ?: return null
        decrypt(u) to decrypt(pw)
    } catch (_: Exception) { null }

    fun user(ctx: Context): String? = load(ctx)?.first

    fun clear(ctx: Context) = ctx.getSharedPreferences(PREFS, 0).edit().clear().apply()
}
