package org.phlo.AirReceiver;

public class AudioFormat {
    private final int m_sampleRate;
    private final int m_sampleBits;
    private final int m_channels;
    private final boolean m_unsigned;
    private final boolean m_bigEndian;

    public AudioFormat(int sampleRate, int sampleBits, int channels, boolean unsigned, boolean bigEndian) {
        m_sampleRate = sampleRate;
        m_sampleBits = sampleBits;
        m_channels = channels;
        m_unsigned = unsigned;
        m_bigEndian = bigEndian;
    }

    public int getChannels() {
        return m_channels;
    }

    public int getSampleRate() {
        return m_sampleRate;
    }

    public int getSampleSizeInBits() {
        return m_sampleBits;
    }
}
