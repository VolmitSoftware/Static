package com.volmit.staticstats.config;

import java.io.File;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class ConfigService {
    private final ConfigRepository repository;
    private final AtomicReference<ConfigRepository.PreparedConfig> current;
    private volatile BiConsumer<File, String> selfWriteListener;
    private volatile Consumer<RuntimeConfig> validator;
    private volatile BiConsumer<RuntimeConfig, Runnable> committer;

    public ConfigService(File dataFolder) {
        this.repository = new ConfigRepository(dataFolder);
        this.current = new AtomicReference<>(repository.defaults());
    }

    public ConfigRepository.PreparedConfig prepareFromDisk() throws IOException {
        return repository.load();
    }

    public synchronized void install(ConfigRepository.PreparedConfig prepared) {
        Objects.requireNonNull(prepared, "Prepared configuration cannot be null");
        validate(prepared.runtime());
        commit(prepared);
    }

    public synchronized RuntimeConfig update(Consumer<StaticConfig> editor) throws IOException {
        ConfigRepository.PreparedConfig previous = current.get();
        StaticConfig candidate = previous.source().copy();
        editor.accept(candidate);
        validate(RuntimeConfig.from(candidate));
        ConfigRepository.PreparedConfig prepared = repository.save(candidate);
        try {
            commit(prepared);
        } catch (RuntimeException | Error exception) {
            try {
                repository.restore(previous);
            } catch (IOException | RuntimeException rollbackFailure) {
                exception.addSuppressed(rollbackFailure);
            }
            throw exception;
        }
        BiConsumer<File, String> listener = selfWriteListener;
        if (listener != null) {
            listener.accept(repository.configFile(), prepared.serialized());
        }
        return prepared.runtime();
    }

    public RuntimeConfig runtime() {
        return current.get().runtime();
    }

    public StaticConfig editableCopy() {
        return current.get().source().copy();
    }

    public File configFile() {
        return repository.configFile();
    }

    public File dataFolder() {
        return repository.dataFolder();
    }

    public void setSelfWriteListener(BiConsumer<File, String> listener) {
        selfWriteListener = listener;
    }

    public void setValidator(Consumer<RuntimeConfig> validator) {
        this.validator = Objects.requireNonNull(validator);
    }

    public void setCommitter(BiConsumer<RuntimeConfig, Runnable> committer) {
        this.committer = Objects.requireNonNull(committer);
    }

    private void commit(ConfigRepository.PreparedConfig prepared) {
        BiConsumer<RuntimeConfig, Runnable> activeCommitter = committer;
        if (activeCommitter == null) {
            current.set(prepared);
            return;
        }
        ConfigRepository.PreparedConfig previous = current.get();
        try {
            activeCommitter.accept(prepared.runtime(), () -> current.set(prepared));
        } catch (RuntimeException | Error exception) {
            current.set(previous);
            throw exception;
        }
    }

    private void validate(RuntimeConfig candidate) {
        Consumer<RuntimeConfig> activeValidator = validator;
        if (activeValidator != null) {
            activeValidator.accept(candidate);
        }
    }
}
