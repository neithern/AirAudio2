/*
 * This file is part of AirReceiver.
 *
 * AirReceiver is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * AirReceiver is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with AirReceiver.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.phlo.AirReceiver;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

import org.jboss.netty.buffer.*;
import org.jboss.netty.channel.*;
import org.jboss.netty.handler.codec.oneone.OneToOneDecoder;

/**
 * De-crypt AES encoded audio data
 */
public class RaopRtpAudioDecryptionHandler extends OneToOneDecoder {
	/**
	 *  The AES cipher. We request no padding because RAOP/AirTunes only encrypts full
	 * block anyway and leaves the trailing byte unencrypted
	 */
	private final Cipher m_aesCipher = AirTunesCrytography.getCipher("AES/CBC/NoPadding");

	private final byte[] m_block = new byte[16];

	public RaopRtpAudioDecryptionHandler(final SecretKey aesKey, final IvParameterSpec aesIv)
		throws Exception
	{
		m_aesCipher.init(Cipher.DECRYPT_MODE, aesKey, aesIv);
	}

	@Override
	protected synchronized Object decode(final ChannelHandlerContext ctx, final Channel channel, final Object msg)
		throws Exception
	{
		if (msg instanceof RaopRtpPacket.Audio) {
			final RaopRtpPacket.Audio audioPacket = (RaopRtpPacket.Audio)msg;
			final ChannelBuffer audioPayload = audioPacket.getPayload();

			final byte[] data = audioPayload.array();
			final int offset = audioPayload.arrayOffset();
			final int size = audioPayload.capacity();
			for (int i = 0; i + 16 <= size; i += 16) {
				/*
				 * Note: Cipher.update(byte[], int, int, byte[], int) method should be copy-safe,
				 * which means the input and output buffers can reference
				 * the same byte array and no unprocessed input data is overwritten
				 * when the result is copied into the output buffer.
				 */
				m_aesCipher.update(data, offset + i, Math.min(16, size - i),
						data, offset + i);
			}

			/* Cipher is restarted for every packet, call doFinal() to reset it. */
			m_aesCipher.doFinal(m_block, 0);
		}

		return msg;
	}
}
