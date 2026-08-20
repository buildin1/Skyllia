package fr.euphyllia.skyllia.utils;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.core.file.FileConfig;
import com.electronwill.nightconfig.core.io.IndentStyle;
import com.electronwill.nightconfig.core.io.WritingMode;
import com.electronwill.nightconfig.toml.TomlWriter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * TOML 配置文件的<b>原子整表落盘</b>工具。
 *
 * <p>核心与各附属插件都会在「补全缺失的默认值」「管理命令改配置」之后把整份配置写回磁盘。
 * 直接 {@code TomlWriter.write(config, file, WritingMode.REPLACE)} 是不安全的：
 * {@link WritingMode#REPLACE} 的语义是<b>先截断目标文件、再往里写</b>，这两步之间如果
 * 崩溃/掉电/被强杀，磁盘上会留下一份半截的 toml，下次启动直接解析失败——对
 * {@code permissions-v2.toml} 这种一坏就全服权限失效的文件是不可接受的。</p>
 *
 * <p>所以这里统一成「先写同目录的 {@code .tmp}，写完再原子改名覆盖目标」：
 * 目标文件在任意时刻要么是改动前的完整内容，要么是改动后的完整内容，不存在中间态。
 * 少数文件系统（部分网络盘、容器挂载）不支持原子改名，此时退化成普通替换——
 * 仍然比直接截断目标文件安全，因为被移动过去的是一份已经完整写好的 tmp。</p>
 *
 * <p>写入失败一律「记录 error + 删掉 tmp + 返回 false」，绝不抛异常：这些调用点大多在
 * {@code /skyllia reload} 的全局重载循环里，抛出去会让排在后面的配置提供者全部不被重载。</p>
 */
public final class ConfigFileWriter {

    private static final Logger log = LogManager.getLogger(ConfigFileWriter.class);

    private ConfigFileWriter() {
    }

    /**
     * 把 {@code config} 原子写回它自己的文件（{@link FileConfig#getFile()}）。
     *
     * @return 是否成功落盘
     */
    public static boolean writeAtomically(@NotNull FileConfig config) {
        return writeAtomically(config, config.getFile());
    }

    /**
     * 把 {@code config} 原子写入 {@code target}。
     *
     * @return 是否成功落盘；失败时 {@code target} 保持改动前的完整内容不变
     */
    public static boolean writeAtomically(@NotNull UnmodifiableConfig config, @NotNull File target) {
        File parent = target.getParentFile();
        File tmp = new File(parent, target.getName() + ".tmp");
        try {
            TomlWriter tomlWriter = new TomlWriter();
            tomlWriter.setIndent(IndentStyle.NONE);
            tomlWriter.write(config, tmp, WritingMode.REPLACE);
            try {
                Files.move(tmp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            log.error("写入 {} 失败，本次改动没有落盘：{}", target.getName(), e.toString());
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
    }
}
