package org.nem.ncc.storable.entity.storage;

import org.apache.commons.io.IOUtils;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.io.*;
import org.bouncycastle.crypto.modes.CBCBlockCipher;
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher;
import org.bouncycastle.crypto.params.*;
import org.nem.core.crypto.Hashes;
import org.nem.core.serialization.*;
import org.nem.core.utils.*;
import org.nem.ncc.storable.entity.*;

import java.io.*;

// TODO 20150115 J-B: i think it makes sense for these to be abstract?

/**
 * StorableEntityDescriptor that automatically encrypts and decrypts data using a password.
 */
public class SecureStorableEntityDescriptor<
		TEntity extends StorableEntity & ObjectDeserializer<TEntity>,
		TEntityName extends StorableEntityName,
		TEntityFileExtension extends StorableEntityFileExtension,
		TEntityPassword extends StorableEntityPassword,
		TEntityDescriptor extends StorableEntityDescriptor<TEntity, TEntityName, TEntityFileExtension>>
		implements StorableEntityDescriptor<TEntity, TEntityName, TEntityFileExtension> {
	private static final int BLOCK_SIZE = 16;

	private final TEntityDescriptor descriptor;
	private final TEntityPassword password;

	/**
	 * Creates a new secure storable entity descriptor.
	 *
	 * @param descriptor The underlying descriptor.
	 * @param password The password.
	 */
	public SecureStorableEntityDescriptor(
			final TEntityDescriptor descriptor,
			final TEntityPassword password) {
		if (null == password) {
			throw this.getException(StorableEntityStorageException.Code.STORABLE_ENTITY_PASSWORD_CANNOT_BE_NULL.value(), null);
		}

		this.descriptor = descriptor;
		this.password = password;
	}

	/**
	 * Gets the entity descriptor.
	 *
	 * @return The entity descriptor.
	 */
	public TEntityDescriptor getDescriptor() {
		return this.descriptor;
	}

	@Override
	public TEntityName getName() {
		return this.descriptor.getName();
	}

	@Override
	public TEntityFileExtension getFileExtension() {
		return this.descriptor.getFileExtension();
	}

	// TODO 20150115 J-B: why do you need to expose the deserializer?
	// > applies to all occurrences of getDeserializer in an interface

	@Override
	public ObjectDeserializer<TEntity> getDeserializer() {
		return this.descriptor.getDeserializer();
	}

	@Override
	public InputStream openRead() {
		return ExceptionUtils.propagate(
				this::openReadInternal,
				ex -> this.getException(
						InvalidCipherTextIOException.class.isAssignableFrom(ex.getClass())
								? StorableEntityStorageException.Code.STORABLE_ENTITY_PASSWORD_INCORRECT.value()
								: StorableEntityStorageException.Code.STORABLE_ENTITY_COULD_NOT_BE_READ.value(),
						ex));
	}

	private InputStream openReadInternal() throws IOException {
		final PaddedBufferedBlockCipher cipher = this.getCipher(false);
		try (final InputStream inputStream = this.descriptor.openRead()) {
			try (final CipherInputStream cis = new CipherInputStream(inputStream, cipher)) {
				final byte[] decryptedBytes = IOUtils.toByteArray(cis);
				return new ByteArrayInputStream(decryptedBytes);
			}
		}
	}

	@Override
	public OutputStream openWrite() {
		return new ByteArrayOutputStream() {
			@Override
			public void close() throws IOException {
				SecureStorableEntityDescriptor.this.closeInternal(this.toByteArray());
				super.close();
			}
		};
	}

	private void closeInternal(final byte[] content) throws IOException {
		final PaddedBufferedBlockCipher cipher = this.getCipher(true);
		try (final OutputStream outputStream = this.descriptor.openWrite()) {
			try (final CipherOutputStream cos = new CipherOutputStream(outputStream, cipher)) {
				cos.write(content);
			}
		}
	}

	@Override
	public void delete() {
		this.descriptor.delete();
	}

	@Override
	public void serialize(final Serializer serializer) {
		this.descriptor.serialize(serializer);
	}

	private PaddedBufferedBlockCipher getCipher(final boolean encrypt) {
		final KeyParameter key = new KeyParameter(Hashes.sha3_256(StringEncoder.getBytes(this.password.toString())));

		// create and initialize the cipher
		final PaddedBufferedBlockCipher resultCipher = new PaddedBufferedBlockCipher(new CBCBlockCipher(new AESEngine()));
		final ParametersWithIV parameterIV = new ParametersWithIV(key, new byte[BLOCK_SIZE]);
		resultCipher.init(encrypt, parameterIV);
		return resultCipher;
	}

	protected StorableEntityStorageException getException(final int value, final Exception ex) {
		return null == ex ? new StorableEntityStorageException(value) : new StorableEntityStorageException(value, ex);
	}
}
