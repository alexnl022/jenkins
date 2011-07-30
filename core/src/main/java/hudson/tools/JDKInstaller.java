/*
 * The MIT License
 *
 * Copyright (c) 2009-2010, Sun Microsystems, Inc., CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.tools;

import com.gargoylesoftware.htmlunit.ElementNotFoundException;
import com.gargoylesoftware.htmlunit.Page;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Launcher.ProcStarter;
import hudson.Util;
import hudson.model.DownloadService.Downloadable;
import hudson.model.JDK;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.remoting.Callable;
import hudson.util.ArgumentListBuilder;
import hudson.util.FormValidation;
import hudson.util.HttpResponses;
import hudson.util.IOException2;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.io.IOUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.Stapler;

import javax.servlet.ServletException;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

import static hudson.tools.JDKInstaller.Preference.*;

/**
 * Install JDKs from java.sun.com.
 *
 * @author Kohsuke Kawaguchi
 * @since 1.305
 */
public class JDKInstaller extends ToolInstaller {
    /**
     * The release ID that Sun assigns to each JDK, such as "jdk-6u13-oth-JPR@CDS-CDS_Developer"
     *
     * <p>
     * This ID can be seen in the "ProductRef" query parameter of the download page, like
     * https://cds.sun.com/is-bin/INTERSHOP.enfinity/WFS/CDS-CDS_Developer-Site/en_US/-/USD/ViewProductDetail-Start?ProductRef=jdk-6u13-oth-JPR@CDS-CDS_Developer
     */
    public final String id;

    /**
     * We require that the user accepts the license by clicking a checkbox, to make up for the part
     * that we auto-accept cds.sun.com license click through.
     */
    public final boolean acceptLicense;

    @DataBoundConstructor
    public JDKInstaller(String id, boolean acceptLicense) {
        super(null);
        this.id = id;
        this.acceptLicense = acceptLicense;
    }

    public FilePath performInstallation(ToolInstallation tool, Node node, TaskListener log) throws IOException, InterruptedException {
        FilePath expectedLocation = preferredLocation(tool, node);
        PrintStream out = log.getLogger();
        try {
            if(!acceptLicense) {
                out.println(Messages.JDKInstaller_UnableToInstallUntilLicenseAccepted());
                return expectedLocation;
            }
            // already installed?
            FilePath marker = expectedLocation.child(".installedByHudson");
            if (marker.exists() && marker.readToString().equals(id)) {
                return expectedLocation;
            }
            expectedLocation.deleteRecursive();
            expectedLocation.mkdirs();

            Platform p = Platform.of(node);
            URL url = locate(log, p, CPU.of(node));

//            out.println("Downloading "+url);
            FilePath file = expectedLocation.child(p.bundleFileName);
            file.copyFrom(url);

            // JDK6u13 on Windows doesn't like path representation like "/tmp/foo", so make it a strict platform native format by doing 'absolutize'
            install(node.createLauncher(log), p, new FilePathFileSystem(node), log, expectedLocation.absolutize().getRemote(), file.getRemote());

            // successfully installed
            file.delete();
            marker.write(id, null);

        } catch (DetectionFailedException e) {
            out.println("JDK installation skipped: "+e.getMessage());
        }

        return expectedLocation;
    }

    /**
     * Performs the JDK installation to a system, provided that the bundle was already downloaded.
     *
     * @param launcher
     *      Used to launch processes on the system.
     * @param p
     *      Platform of the system. This determines how the bundle is installed.
     * @param fs
     *      Abstraction of the file system manipulation on this system.
     * @param log
     *      Where the output from the installation will be written.
     * @param expectedLocation
     *      Path to install JDK to. Must be absolute and in the native file system notation.
     * @param jdkBundle
     *      Path to the installed JDK bundle. (The bundle to download can be determined by {@link #locate(TaskListener, Platform, CPU)} call.)
     */
    public void install(Launcher launcher, Platform p, FileSystem fs, TaskListener log, String expectedLocation, String jdkBundle) throws IOException, InterruptedException {
        PrintStream out = log.getLogger();

        out.println("Installing "+ jdkBundle);
        switch (p) {
        case LINUX:
        case SOLARIS:
            // JDK on Unix up to 6 was distributed as shell script installer, but in JDK7 it switched to a plain tgz.
            // so check if the file is gzipped, and if so, treat it accordingly
            byte[] header = new byte[2];
            {
                DataInputStream in = new DataInputStream(fs.read(jdkBundle));
                in.readFully(header);
                in.close();
            }

            ProcStarter starter;
            if (header[0]==0x1F && header[1]==(byte)0x8B) {// gzip
                starter = launcher.launch().cmds("tar", "xvzf", jdkBundle);
            } else {
                fs.chmod(jdkBundle,0755);
                starter = launcher.launch().cmds(jdkBundle, "-noregister");
            }

            int exit = starter
                    .stdin(new ByteArrayInputStream("yes".getBytes())).stdout(out)
                    .pwd(new FilePath(launcher.getChannel(), expectedLocation)).join();
            if (exit != 0)
                throw new AbortException(Messages.JDKInstaller_FailedToInstallJDK(exit));

            // JDK creates its own sub-directory, so pull them up
            List<String> paths = fs.listSubDirectories(expectedLocation);
            for (Iterator<String> itr = paths.iterator(); itr.hasNext();) {
                String s =  itr.next();
                if (!s.matches("j(2s)?dk.*"))
                    itr.remove();
            }
            if(paths.size()!=1)
                throw new AbortException("Failed to find the extracted JDKs: "+paths);

            // remove the intermediate directory
            fs.pullUp(expectedLocation+'/'+paths.get(0),expectedLocation);
            break;
        case WINDOWS:
            /*
                Windows silent installation is full of bad know-how.

                On Windows, command line argument to a process at the OS level is a single string,
                not a string array like POSIX. When we pass arguments as string array, JRE eventually
                turn it into a single string with adding quotes to "the right place". Unfortunately,
                with the strange argument layout of InstallShield (like /v/qn" INSTALLDIR=foobar"),
                it appears that the escaping done by JRE gets in the way, and prevents the installation.
                Presumably because of this, my attempt to use /q/vn" INSTALLDIR=foo" didn't work with JDK5.

                I tried to locate exactly how InstallShield parses the arguments (and why it uses
                awkward option like /qn, but couldn't find any. Instead, experiments revealed that
                "/q/vn ARG ARG ARG" works just as well. This is presumably due to the Visual C++ runtime library
                (which does single string -> string array conversion to invoke the main method in most Win32 process),
                and this consistently worked on JDK5 and JDK4.

                Some of the official documentations are available at
                - http://java.sun.com/j2se/1.5.0/sdksilent.html
                - http://java.sun.com/j2se/1.4.2/docs/guide/plugin/developer_guide/silent.html
             */
            String logFile = jdkBundle+".install.log";

            ArgumentListBuilder args = new ArgumentListBuilder();
            args.add(jdkBundle);
            args.add("/s");
            // according to http://community.acresso.com/showthread.php?t=83301, \" is the trick to quote values with whitespaces.
            // Oh Windows, oh windows, why do you have to be so difficult?
            args.add("/v/qn REBOOT=Suppress INSTALLDIR=\\\""+ expectedLocation +"\\\" /L \\\""+logFile+"\\\"");

            int r = launcher.launch().cmds(args).stdout(out)
                    .pwd(new FilePath(launcher.getChannel(), expectedLocation)).join();
            if (r != 0) {
                out.println(Messages.JDKInstaller_FailedToInstallJDK(r));
                // log file is in UTF-16
                InputStreamReader in = new InputStreamReader(fs.read(logFile), "UTF-16");
                try {
                    IOUtils.copy(in,new OutputStreamWriter(out));
                } finally {
                    in.close();
                }
                throw new AbortException();
            }

            fs.delete(logFile);

            break;
        }
    }

    /**
     * Abstraction of the file system to perform JDK installation.
     * Consider {@link FilePathFileSystem} as the canonical documentation of the contract.
     */
    public interface FileSystem {
        void delete(String file) throws IOException, InterruptedException;
        void chmod(String file,int mode) throws IOException, InterruptedException;
        InputStream read(String file) throws IOException;
        /**
         * List sub-directories of the given directory and just return the file name portion.
         */
        List<String> listSubDirectories(String dir) throws IOException, InterruptedException;
        void pullUp(String from, String to) throws IOException, InterruptedException;
    }

    /*package*/ static final class FilePathFileSystem implements FileSystem {
        private final Node node;

        FilePathFileSystem(Node node) {
            this.node = node;
        }

        public void delete(String file) throws IOException, InterruptedException {
            $(file).delete();
        }

        public void chmod(String file, int mode) throws IOException, InterruptedException {
            $(file).chmod(mode);
        }

        public InputStream read(String file) throws IOException {
            return $(file).read();
        }

        public List<String> listSubDirectories(String dir) throws IOException, InterruptedException {
            List<String> r = new ArrayList<String>();
            for( FilePath f : $(dir).listDirectories())
                r.add(f.getName());
            return r;
        }

        public void pullUp(String from, String to) throws IOException, InterruptedException {
            $(from).moveAllChildrenTo($(to));
        }

        private FilePath $(String file) {
            return node.createPath(file);
        }
    }

    /**
     * This is where we locally cache this JDK.
     */
    private File getLocalCacheFile(Platform platform, CPU cpu) {
        return new File(Jenkins.getInstance().getRootDir(),"cache/jdks/"+platform+"/"+cpu+"/"+id);
    }

    /**
     * Performs a license click through and obtains the one-time URL for downloading bits.
     */
    public URL locate(TaskListener log, Platform platform, CPU cpu) throws IOException {
        File cache = getLocalCacheFile(platform, cpu);
        if (cache.exists()) return cache.toURL();

        log.getLogger().println("Installing JDK "+id);
        JDKFamilyList families = JDKList.all().get(JDKList.class).toList();
        if (families.isEmpty())
            throw new IOException("JDK data is empty.");

        JDKRelease release = families.getRelease(id);
        if (release==null)
            throw new IOException("Unable to find JDK with ID="+id);

        JDKFile primary=null,secondary=null;
        for (JDKFile f : release.files) {
            String vcap = f.name.toUpperCase(Locale.ENGLISH);

            // JDK files have either 'windows', 'linux', or 'solaris' in its name, so that allows us to throw
            // away unapplicable stuff right away
            if(!platform.is(vcap))
                continue;

            switch (cpu.accept(vcap)) {
            case PRIMARY:   primary = f;break;
            case SECONDARY: secondary=f;break;
            case UNACCEPTABLE:  break;
            }
        }

        if(primary==null)   primary=secondary;
        if(primary==null)
            throw new AbortException("Couldn't find the right download for "+platform+" and "+ cpu +" combination");
        LOGGER.fine("Platform choice:"+primary);

        log.getLogger().println("Downloading JDK from "+primary.filepath);
        URL src = new URL(primary.filepath);

        WebClient wc = new WebClient();
        wc.setJavaScriptEnabled(false);
        wc.setCssEnabled(false);
        Page page = wc.getPage(src);
        int authCount=0;
        int totalPageCount=0;
        while (page instanceof HtmlPage) {
            // some times we are redirected to the SSO login page.
            HtmlPage html = (HtmlPage) page;
            URL loginUrl = page.getWebResponse().getUrl();
            if (!loginUrl.getHost().equals("login.oracle.com"))
                throw new IOException("Expected to see a login page but instead saw "+loginUrl);

            String u = getDescriptor().getUsername();
            Secret p = getDescriptor().getPassword();
            if (u==null || p==null) {
                log.hyperlink(getCredentialPageUrl(),"Oracle now requires Oracle account to download previous versions of JDK. Please specify your Oracle account username/password.");
                throw new AbortException("Unable to install JDK unless a valid username/password is provided.");
            }

            if (totalPageCount>16) // looping too much
                throw new IOException("Unable to find the login form in "+html.asXml());

            try {
                // JavaScript check page. Just submit and move on
                HtmlForm loginForm = html.getFormByName("myForm");
                loginForm.getInputByName("ssousername").setValueAttribute(u);
                page = loginForm.submit(null);
                continue;
            } catch (ElementNotFoundException e) {
                // fall through
            }

            try {
                // real authentication page
                if (authCount++ > 3) {
                    log.hyperlink(getCredentialPageUrl(),"Your Oracle account doesn't appear valid. Please specify a valid username/password");
                    throw new AbortException("Unable to install JDK unless a valid username/password is provided.");
                }
                HtmlForm loginForm = html.getFormByName("LoginForm");
                loginForm.getInputByName("ssousername").setValueAttribute(u);
                loginForm.getInputByName("password").setValueAttribute(p.getPlainText());
                page = loginForm.submit(null);
                continue;
            } catch (ElementNotFoundException e) {
                // fall through
            }

            throw new IOException("Unable to find the login form in "+html.asXml());
        }

        // TODO: there's awful inefficiency in htmlunit where it loads the whole binary into one big byte array.
        // needs to modify it to use temporary file or something

        // download to a temporary file and rename it in to handle concurrency and failure correctly,
        File tmp = new File(cache.getPath()+".tmp");
        tmp.getParentFile().mkdirs();
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                IOUtils.copy(page.getWebResponse().getContentAsStream(), out);
            } finally {
                out.close();
            }

            tmp.renameTo(cache);
            return cache.toURL();
        } finally {
            tmp.delete();
        }
    }

    private String getCredentialPageUrl() {
        return "/"+getDescriptor().getDescriptorUrl()+"/enterCredential";
    }

    public enum Preference {
        PRIMARY, SECONDARY, UNACCEPTABLE
    }

    /**
     * Supported platform.
     */
    public enum Platform {
        LINUX("jdk.sh"), SOLARIS("jdk.sh"), WINDOWS("jdk.exe");

        /**
         * Choose the file name suitable for the downloaded JDK bundle.
         */
        public final String bundleFileName;

        Platform(String bundleFileName) {
            this.bundleFileName = bundleFileName;
        }

        public boolean is(String line) {
            return line.contains(name());
        }

        /**
         * Determines the platform of the given node.
         */
        public static Platform of(Node n) throws IOException,InterruptedException,DetectionFailedException {
            return n.getChannel().call(new Callable<Platform,DetectionFailedException>() {
                public Platform call() throws DetectionFailedException {
                    return current();
                }
            });
        }

        public static Platform current() throws DetectionFailedException {
            String arch = System.getProperty("os.name").toLowerCase(Locale.ENGLISH);
            if(arch.contains("linux"))  return LINUX;
            if(arch.contains("windows"))   return WINDOWS;
            if(arch.contains("sun") || arch.contains("solaris"))    return SOLARIS;
            throw new DetectionFailedException("Unknown CPU name: "+arch);
        }
    }

    /**
     * CPU type.
     */
    public enum CPU {
        i386, amd64, Sparc, Itanium;

        /**
         * In JDK5u3, I see platform like "Linux AMD64", while JDK6u3 refers to "Linux x64", so
         * just use "64" for locating bits.
         */
        public Preference accept(String line) {
            switch (this) {
            // these two guys are totally incompatible with everything else, so no fallback
            case Sparc:     return must(line.contains("SPARC"));
            case Itanium:   return must(line.contains("IA64"));

            // 64bit Solaris, Linux, and Windows can all run 32bit executable, so fall back to 32bit if 64bit bundle is not found
            case amd64:
                if(line.contains("SPARC") || line.contains("IA64"))  return UNACCEPTABLE;
                if(line.contains("64"))     return PRIMARY;
                return SECONDARY;
            case i386:
                if(line.contains("64") || line.contains("SPARC") || line.contains("IA64"))     return UNACCEPTABLE;
                return PRIMARY;
            }
            return UNACCEPTABLE;
        }

        private static Preference must(boolean b) {
             return b ? PRIMARY : UNACCEPTABLE;
        }

        /**
         * Determines the CPU of the given node.
         */
        public static CPU of(Node n) throws IOException,InterruptedException, DetectionFailedException {
            return n.getChannel().call(new Callable<CPU,DetectionFailedException>() {
                public CPU call() throws DetectionFailedException {
                    return current();
                }
            });
        }

        /**
         * Determines the CPU of the current JVM.
         *
         * http://lopica.sourceforge.net/os.html was useful in writing this code.
         */
        public static CPU current() throws DetectionFailedException {
            String arch = System.getProperty("os.arch").toLowerCase(Locale.ENGLISH);
            if(arch.contains("sparc"))  return Sparc;
            if(arch.contains("ia64"))   return Itanium;
            if(arch.contains("amd64") || arch.contains("86_64"))    return amd64;
            if(arch.contains("86"))    return i386;
            throw new DetectionFailedException("Unknown CPU architecture: "+arch);
        }
    }

    /**
     * Indicates the failure to detect the OS or CPU.
     */
    private static final class DetectionFailedException extends Exception {
        private DetectionFailedException(String message) {
            super(message);
        }
    }

    public static final class JDKFamilyList {
        public int version;
        public JDKFamily[] data = new JDKFamily[0];

        public boolean isEmpty() {
            for (JDKFamily f : data) {
                if (f.releases.length>0)
                    return false;
            }
            return true;
        }

        public JDKRelease getRelease(String productCode) {
            for (JDKFamily f : data) {
                for (JDKRelease r : f.releases) {
                    if (r.matchesId(productCode))
                        return r;
                }
            }
            return null;
        }
    }

    public static final class JDKFamily {
        public String name;
        public JDKRelease[] releases;
    }

    public static final class JDKRelease {
        /**
         * This maps to the former product code, like "jdk-6u13-oth-JPR"
         */
        public String name;
        /**
         * This is human readable.
         */
        public String title;
        public JDKFile[] files;

        /**
         * We used to use IDs like "jdk-6u13-oth-JPR@CDS-CDS_Developer", but Oracle switched to just "jdk-6u13-oth-JPR".
         * This method matches if the specified string matches the name, and it accepts both the old and the new format.
         */
        public boolean matchesId(String rhs) {
            return rhs!=null && (rhs.equals(name) || rhs.startsWith(name+"@"));
        }
    }

    public static final class JDKFile {
        public String name;
        public String title;
        public String filepath;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    @Extension
    public static final class DescriptorImpl extends ToolInstallerDescriptor<JDKInstaller> {
        private String username;
        private Secret password;

        public DescriptorImpl() {
            load();
        }

        public String getDisplayName() {
            return Messages.JDKInstaller_DescriptorImpl_displayName();
        }

        @Override
        public boolean isApplicable(Class<? extends ToolInstallation> toolType) {
            return toolType==JDK.class;
        }

        public String getUsername() {
            return username;
        }

        public Secret getPassword() {
            return password;
        }

        public FormValidation doCheckId(@QueryParameter String value) {
            if (Util.fixEmpty(value) == null)
                return FormValidation.error(Messages.JDKInstaller_DescriptorImpl_doCheckId()); // improve message
            return FormValidation.ok();
        }

        /**
         * List of installable JDKs.
         * @return never null.
         */
        public List<JDKFamily> getInstallableJDKs() throws IOException {
            return Arrays.asList(JDKList.all().get(JDKList.class).toList().data);
        }

        public FormValidation doCheckAcceptLicense(@QueryParameter boolean value) {
            if (username==null || password==null)
                return FormValidation.errorWithMarkup(Messages.JDKInstaller_RequireOracleAccount(Stapler.getCurrentRequest().getContextPath()+getDescriptorUrl()+"/enterCredential"));
            if (value) {
                return FormValidation.ok();
            } else {
                return FormValidation.error(Messages.JDKInstaller_DescriptorImpl_doCheckAcceptLicense()); 
            }
        }

        /**
         * Submits the Oracle account username/password.
         */
        public HttpResponse doPostCredential(@QueryParameter String username, @QueryParameter String password) throws IOException, ServletException {
            this.username = username;
            this.password = Secret.fromString(password);
            save();
            return HttpResponses.redirectViaContextPath("configure");
        }
    }

    /**
     * JDK list.
     */
    @Extension
    public static final class JDKList extends Downloadable {
        public JDKList() {
            super(JDKInstaller.class);
        }

        public JDKFamilyList toList() throws IOException {
            JSONObject d = getData();
            if(d==null) return new JDKFamilyList();
            return (JDKFamilyList)JSONObject.toBean(d,JDKFamilyList.class);
        }
    }

    private static final Logger LOGGER = Logger.getLogger(JDKInstaller.class.getName());
}
