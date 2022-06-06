package tura.miner.util;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;
import org.yaml.snakeyaml.Yaml;

import tura.miner.MinerMonitor;

public class Util {

	private static final String app_path = "/app";
	private static final String plot_path = "/plots";
	private static final String conf_fn = "config.yaml";
	private static final String miner_fn = "rotura-miner";

	public static final synchronized Path checkAndCopyMinerFiles(Path p) throws IOException {
		for (int i = 0; i < 10; i++) {
			if (!Files.exists(p)) {
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
				}
			}
		}
		if (!Files.exists(p)) {
			throw new FileNotFoundException();
		}
		if (!Files.isDirectory(p)) {
			throw new IOException("NOT DIR!");
		}
		Path conf_file = Files.list(p).filter(o -> o.toFile().getName().equals(conf_fn)).findFirst().get();
		Path miner_bin = Files.list(p).filter(o -> o.toFile().getName().equals(miner_fn)).findFirst().get();
		if (!Files.isReadable(conf_file)) {
			throw new IOException("Can not read " + conf_fn);
		} else if (!Files.isReadable(miner_bin)) {
			throw new IOException("Can not read " + miner_fn);
		}

		Map<String, Object> conf = null;

		try {
			conf = new Yaml().load(Files.readString(conf_file));
		} catch (IOException e) {
			throw new IOException("Can not parse " + conf_fn, e);
		}

		conf.put("plot_dirs", Arrays.asList(plot_path));
		conf.put("show_progress", true);
		conf.put("console_log_level", "info");
		conf.put("console_log_pattern", "{m}{n}");

		Path tempDir = Files.createTempDirectory(UUID.randomUUID().toString());
		tempDir.toFile().deleteOnExit();

		Path p1 = Files.writeString(Path.of(tempDir.toAbsolutePath().toString(), conf_fn), new Yaml().dump(conf));
		p1.toFile().deleteOnExit();
		Path p2 = Files.copy(miner_bin, Path.of(tempDir.toAbsolutePath().toString(), miner_fn), StandardCopyOption.REPLACE_EXISTING);
		p2.toFile().deleteOnExit();

		return tempDir;
	}

	public static final MinerMonitor execDockerMiner(Path miner_dir, Path plot_dir) throws IOException {
		Path cid_path = Files.createTempFile("", ".cid");
		cid_path.toFile().delete();
		Process process = new ProcessBuilder("docker", "run", "--cidfile", cid_path.toFile().getAbsolutePath(), "--rm", "-t", "--read-only", "--mount",
				"type=bind,source=" + plot_dir.toFile().getAbsolutePath() + ",target=" + plot_path, "--mount", "type=bind,source=" + miner_dir.toFile().getAbsolutePath() + ",target=" + app_path, "-w",
				app_path, "amd64/ubuntu", "/app/rotura-miner").start();
		InputStream in = process.getInputStream();
		while (in.available() < 1) {
			try {
				Thread.sleep(1);
			} catch (InterruptedException e) {
			}
		}
		String cid = Files.readString(cid_path).trim();
		cid_path.toFile().delete();
		MinerMonitor mon = new MinerMonitor(cid, plot_dir, new BufferedReader(new InputStreamReader(in)));
		Map<String, Object> conf = new Yaml().load(Files.readString(Paths.get(miner_dir.toAbsolutePath().toString(), conf_fn)));
		@SuppressWarnings({ "unchecked", "rawtypes" })
		List<String> accounts = (List<String>) ((Map) conf.get("account_id_to_secret_phrase")).keySet().stream().map(o -> o.toString()).collect(Collectors.toUnmodifiableList());
		mon.getConf().put("accounts", accounts);
		mon.getConf().put("url", conf.get("url"));
		return mon;
	}

	public static final synchronized int killDockerMiner(String cid) throws Exception {
		Process process = new ProcessBuilder("docker", "kill", cid).start();
		return process.waitFor();
	}

	public static final Map<String, Long> systemMemory() {
		Map<String, Long> map = new TreeMap<>();
		try {
			Process process = new ProcessBuilder("free", "-b", "-t").start();
			String[] line = IOUtils.readLines(process.getInputStream(), "UTF-8").stream().filter(s -> s.startsWith("Total:")).findAny().get().split("\\s+");
			map.put("total", Long.valueOf(line[1]));
			map.put("used", Long.valueOf(line[2]));
			map.put("free", Long.valueOf(line[3]));
		} catch (IOException e) {
		}
		return map;
	}

	public static final Map<String, Map<String, String>> diskUsage() {
		Map<String, Map<String, String>> map = new TreeMap<>();
		try {
			Process process = new ProcessBuilder("df", "-h").start();
			IOUtils.readLines(process.getInputStream(), "UTF-8").stream().map(s -> s.split("\\s+")).forEach(s -> {
				Map<String, String> m = new TreeMap<>();
				m.put("size", s[1]);
				m.put("used", s[2]);
				m.put("avail", s[3]);
				m.put("ratio", s[4]);
				map.put(s[5], m);
			});
		} catch (IOException e) {
		}
		return map;
	}

	public static final Callable<String> pingServer(String url) {
		return new Callable<String>() {

			@Override
			public String call() throws Exception {
				String host = new URL(url).getHost();
				Process process = new ProcessBuilder("ping", "-c", "1", host).start();
				List<String> lines = IOUtils.readLines(process.getInputStream(), "UTF-8");
				String str = lines.get(lines.size()-1);
				str = str.substring(str.indexOf("=")+1);
				str = str.substring(0,str.indexOf("/"));
				str = str.trim();
				return str;
			}
		};
	}
}
