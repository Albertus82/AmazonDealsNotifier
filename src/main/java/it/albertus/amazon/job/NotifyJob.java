package it.albertus.amazon.job;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import org.apache.commons.mail.EmailException;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import it.albertus.amazon.email.EmailSender;
import it.albertus.amazon.email.NotifyEmail;
import it.albertus.amazon.util.NotifierConfiguration;
import it.albertus.util.Configuration;
import it.albertus.util.IOUtils;

public class NotifyJob implements Job {

	private static final Logger logger = LoggerFactory.getLogger(NotifyJob.class);

	private static final Configuration configuration = NotifierConfiguration.getInstance();

	private static final EmailSender emailSender = EmailSender.getInstance();

	private static final int BUFFER_SIZE = 1024 * 4;

	public static class Defaults {
		public static final long GET_INTERVAL = 2500L;
		public static final int GET_CONNECT_TIMEOUT = 30000;
		public static final int GET_READ_TIMEOUT = 30000;
		public static final String PRODUCTS_FILENAME = "products.txt";

		private Defaults() {
			throw new IllegalAccessError("Constants class");
		}
	}

	@Override
	public void execute(JobExecutionContext context) throws JobExecutionException {
		logger.info("Job started at {}", new Date());
		final File urlsFile = new File(configuration.getString("products.filename", Defaults.PRODUCTS_FILENAME));

		final Set<String> products = new HashSet<>();
		try (final FileReader fr = new FileReader(urlsFile); final BufferedReader br = new BufferedReader(fr)) {
			String line;
			while ((line = br.readLine()) != null) {
				final String trimmed = line.trim();
				if (!trimmed.isEmpty()) {
					products.add(trimmed);
				}
			}
		}
		catch (final IOException ioe) {
			throw new RuntimeException(ioe);
		}

		int i = 0;
		for (final String element : products) {
			final String productUrl;
			final String emailAddress;
			if (element.indexOf('|') != -1) {
				productUrl = element.substring(0, element.indexOf('|')).trim();
				emailAddress = element.substring(element.indexOf('|') + 1);
			}
			else {
				productUrl = element;
				emailAddress = null;
			}

			logger.info("Connecting to: {}", productUrl);
			try {
				final HttpURLConnection conn = (HttpURLConnection) new URL(productUrl).openConnection();
				conn.setConnectTimeout(configuration.getInt("get.connect.timeout", Defaults.GET_CONNECT_TIMEOUT));
				conn.setReadTimeout(configuration.getInt("get.read.timeout", Defaults.GET_READ_TIMEOUT));
				conn.addRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 6.1; Win64; x64; rv:50.0) Gecko/20100101 Firefox/50.0");
				conn.addRequestProperty("Accept", "*/*");
				conn.addRequestProperty("Accept-Encoding", "gzip");
				final String responseContentEncoding = conn.getHeaderField("Content-Encoding");
				final boolean gzip = responseContentEncoding != null && responseContentEncoding.toLowerCase().contains("gzip");
				try (final InputStream is = gzip ? new GZIPInputStream(conn.getInputStream()) : conn.getInputStream(); final ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
					IOUtils.copy(is, baos, BUFFER_SIZE);
					logger.debug("Response size: {} bytes", baos.size());
					if (baos.toString("UTF-8").contains("priceblock_dealprice")) {
						logger.warn("Deal! {}", productUrl);
						final NotifyEmail email = new NotifyEmail(emailAddress, "Deal Notify", productUrl, null);
						emailSender.send(email);
						logger.debug("Email sent: {}", email);
					}
					else {
						logger.info("No deal for {}", productUrl);
					}
				}
				catch (final EmailException ee) {
					logger.error("Cannot send email", ee);
				}
			}
			catch (final IOException ioe) {
				logger.error("Skipped URL: " + productUrl, ioe);
			}
			if (++i != products.size()) {
				try {
					TimeUnit.MILLISECONDS.sleep(configuration.getLong("get.interval", Defaults.GET_INTERVAL));
				}
				catch (final InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
		logger.info("Job completed at {}", new Date());
	}

}
