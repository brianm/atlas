package com.ning.atlas.chef;

import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import com.ning.atlas.Host;
import com.ning.atlas.SSH;
import com.ning.atlas.SystemMap;
import com.ning.atlas.base.Maybe;
import com.ning.atlas.space.Missing;
import com.ning.atlas.spi.BaseComponent;
import com.ning.atlas.spi.Component;
import com.ning.atlas.spi.Deployment;
import com.ning.atlas.spi.Installer;
import com.ning.atlas.spi.Server;
import com.ning.atlas.spi.Space;
import com.ning.atlas.spi.Uri;
import org.antlr.stringtemplate.StringTemplate;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.SerializationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Arrays.asList;

public class UbuntuChefSoloInstaller extends BaseComponent implements Installer
{
    private final static ObjectMapper mapper = new ObjectMapper();

    static {
        mapper.configure(SerializationConfig.Feature.INDENT_OUTPUT, true);
    }

    private final static Logger logger = LoggerFactory.getLogger(UbuntuChefSoloInstaller.class);

    private static final ExecutorService es = Executors.newCachedThreadPool();

    private final String sshUser;
    private final String sshKeyFile;
    private final File   chefSoloInitFile;
    private final File   soloRbFile;
    private final File   s3InitFile;

    public UbuntuChefSoloInstaller(Map<String, String> attributes)
    {
        this.sshUser = attributes.get("ssh_user");
        checkNotNull(sshUser, "ssh_user attribute required");

        this.sshKeyFile = attributes.get("ssh_key_file");
        checkNotNull(sshKeyFile, "ssh_key_file attribute required");

        final String recipeUrl = attributes.get("recipe_url");
        checkNotNull(recipeUrl, "recipe_url attribute required");

        Maybe<String> s3AccessKey = Maybe.elideNull(attributes.get("s3_access_key"));
        Maybe<String> s3SecretKey = Maybe.elideNull(attributes.get("s3_secret_key"));

        try {
            this.chefSoloInitFile = File.createTempFile("chef-solo-init", "sh");
            InputStream in = UbuntuChefSoloInstaller.class.getResourceAsStream("/ubuntu-chef-solo-setup.sh");
            Files.write(ByteStreams.toByteArray(in), this.chefSoloInitFile);
            in.close();

            this.soloRbFile = File.createTempFile("solo", "rb");
            InputStream in2 = UbuntuChefSoloInstaller.class.getResourceAsStream("/ubuntu-chef-solo-solo.st");
            StringTemplate template = new StringTemplate(new String(ByteStreams.toByteArray(in2)));
            template.setAttribute("recipe_url", recipeUrl);
            Files.write(template.toString().getBytes(), this.soloRbFile);
            in2.close();

            this.s3InitFile = File.createTempFile("s3_init", ".rb");
            if (s3AccessKey.isKnown() && s3SecretKey.isKnown()) {
                InputStream in3 = UbuntuChefSoloInstaller.class.getResourceAsStream("/s3_init.rb.st");
                StringTemplate s3_init_template = new StringTemplate(new String(ByteStreams.toByteArray(in3)));
                s3_init_template.setAttribute("aws_access_key", s3AccessKey.otherwise(""));
                s3_init_template.setAttribute("aws_secret_key", s3SecretKey.otherwise(""));
                Files.write(s3_init_template.toString().getBytes(), this.s3InitFile);
            }
            else {
                Files.write("".getBytes(), this.s3InitFile);
            }

        }
        catch (IOException e) {
            throw new IllegalStateException("Unable to create temp file", e);
        }

    }

//    @Override
//    public void install(final Server server,
//                        final String arg,
//                        com.ning.atlas.spi.Node root,
//                        com.ning.atlas.spi.Node node) throws Exception
//    {
//        boolean done = true;
//        do {
//            String sys_map = mapper.writeValueAsString(root);
//            File sys_map_file = File.createTempFile("system", "map");
//            Files.write(sys_map, sys_map_file, Charset.forName("UTF-8"));
//            initServer(server, createNodeJsonFor(arg), sys_map_file);
//            sys_map_file.delete();
//        }
//        while (!done);
//    }

    @Override
    public Future<String> describe(Host server,
                                   Uri<? extends Component> uri,
                                   Deployment deployment)
    {
        return Futures.immediateFuture("install chef solo and assign it <roles>");
    }

    @Override
    public Future<String> install(final Host server, final Uri<Installer> uri, final Deployment deployment)
    {
        return es.submit(new Callable<String>()
        {
            @Override
            public String call() throws Exception
            {
                return initServer(server, createNodeJsonFor(uri.getFragment()), deployment);

            }
        });
    }


    @Override
    protected void finishLocal(Deployment deployment)
    {
        es.shutdown();
    }

    private String initServer(Host host, String nodeJson, Deployment d) throws IOException
    {
        Server server = d.getSpace().get(host.getId(), Server.class, Missing.RequireAll).getValue();
        SSH ssh = new SSH(new File(sshKeyFile), sshUser, server.getExternalAddress());
        try {
            String remote_path = "/home/" + sshUser + "/ubuntu-chef-solo-init.sh";
            ssh.scpUpload(this.chefSoloInitFile, remote_path);
            ssh.exec("chmod +x " + remote_path);

            logger.debug("about to execute chef init script remotely");
            ssh.exec(remote_path);

            File node_json = File.createTempFile("node", "json");
            Files.write(nodeJson, node_json, Charset.forName("UTF-8"));
            ssh.scpUpload(node_json, "/tmp/node.json");
            ssh.exec("sudo mv /tmp/node.json /etc/chef/node.json");

            ssh.scpUpload(soloRbFile, "/tmp/solo.rb");
            ssh.exec("sudo mv /tmp/solo.rb /etc/chef/solo.rb");

            ssh.scpUpload(s3InitFile, "/tmp/s3_init.rb");
            ssh.exec("sudo mv /tmp/s3_init.rb /etc/chef/s3_init.rb");

            logger.debug("about to execute initial chef-solo");
            return ssh.exec("sudo chef-solo");
        }
        finally {
            ssh.close();
        }
    }

    public String createNodeJsonFor(String literal)
    {
        final Node node;
        try {
            if (literal.contains("run_list")) {
                node = mapper.readValue(literal, Node.class);
            }
            else {
                node = new Node();
                Iterable<String> split = Splitter.on(Pattern.compile(",\\s*")).split(literal);
                Iterables.addAll(node.run_list, split);
            }
            return mapper.writeValueAsString(node);
        }
        catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static final class Node
    {
        public List<String> run_list = Lists.newArrayList();

        public Node(String... elems)
        {
            run_list.addAll(asList(elems));
        }

        public Node()
        {
            this(new String[]{});
        }

        @Override
        public String toString()
        {
            return ToStringBuilder.reflectionToString(this);
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Node node = (Node) o;
            return !(run_list != null ? !run_list.equals(node.run_list) : node.run_list != null);

        }

        @Override
        public int hashCode()
        {
            return run_list != null ? run_list.hashCode() : 0;
        }
    }
}
