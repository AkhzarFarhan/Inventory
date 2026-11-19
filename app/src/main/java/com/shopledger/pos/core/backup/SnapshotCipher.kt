package com.shopledger.pos.core.backup

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stream-based encryption so a snapshot never has to sit in memory as one byte array.
 * Layout on disk: 12-byte IV, then the AES-GCM ciphertext and its 128-bit tag.
 */
interface SnapshotCipher {
    /** Writes the IV to [out] and returns a stream that encrypts into it. Closing it finishes the tag and closes [out]. */
    fun encrypt(out: OutputStream): OutputStream

    /** Reads the IV from [input] and returns a stream of plaintext. A bad tag or wrong key surfaces as an IOException. */
    fun decrypt(input: InputStream): InputStream
}

/** The AES/GCM/NoPadding stream plumbing shared by the Keystore cipher and the test cipher. */
internal object GcmStreams {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun encrypt(key: SecretKey, out: OutputStream): OutputStream {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        out.write(cipher.iv)
        return CipherOutputStream(out, cipher)
    }

    fun decrypt(key: SecretKey, input: InputStream): InputStream {
        val iv = ByteArray(IV_BYTES)
        DataInputStream(input).readFully(iv)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return CipherInputStream(input, cipher)
    }
}

/** AES-256-GCM with a non-exportable Android Keystore key. Output = 12-byte IV + ciphertext. */
class KeystoreSnapshotCipher(private val alias: String = "shopledger_snapshot_v1") : SnapshotCipher {

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    override fun encrypt(out: OutputStream): OutputStream = GcmStreams.encrypt(key(), out)

    override fun decrypt(input: InputStream): InputStream = GcmStreams.decrypt(key(), input)
}
