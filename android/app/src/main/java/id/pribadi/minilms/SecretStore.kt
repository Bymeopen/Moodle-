package id.pribadi.minilms

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Simpan rahasia (kunci API) terenkripsi AES-GCM dengan kunci di Android Keystore (tidak bisa diekspor dari perangkat). */
object SecretStore {
    private const val ALIAS = "minilms_secret_key"
    private const val PREFS = "secrets"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return gen.generateKey()
    }

    private fun resetKey() { runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) } }

    /** Simpan terenkripsi. Keystore yang bermasalah (kunci rusak setelah restore, bug perangkat) dicoba sekali lagi dengan kunci baru. */
    fun put(ctx: Context, name: String, value: String) {
        try { putOnce(ctx, name, value) } catch (e: Exception) { resetKey(); putOnce(ctx, name, value) }
    }

    private fun putOnce(ctx: Context, name: String, value: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val blob = c.iv + c.doFinal(value.toByteArray())
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    fun get(ctx: Context, name: String): String? = try {
        val blob = Base64.decode(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(name, null) ?: return null, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0, 12))) }
        String(c.doFinal(blob.copyOfRange(12, blob.size)))
    } catch (e: Exception) { null }   // kunci hilang/rusak (mis. restore perangkat): anggap tidak ada, pengguna memasukkan ulang

    fun remove(ctx: Context, name: String) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(name).apply() }
}
