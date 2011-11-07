package com.ning.atlas;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.ning.atlas.aws.EC2Helper;
import com.ning.atlas.space.InMemorySpace;
import com.ning.atlas.spi.Deployment;
import com.ning.atlas.spi.Identity;
import com.ning.atlas.spi.Installer;
import com.ning.atlas.spi.My;
import com.ning.atlas.spi.Status;
import com.ning.atlas.spi.protocols.Server;
import com.ning.atlas.spi.Space;
import com.ning.atlas.spi.Uri;
import org.codehaus.jackson.map.ObjectMapper;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import static com.ning.atlas.aws.TestEC2Provisioner.isAvailable;
import static java.util.Arrays.asList;
import static org.junit.Assume.assumeThat;

public class TestAtlasInstaller
{
    @Test
    public void testSerializationInAtlasInstaller() throws Exception
    {
        // the two props are required. Yea!
        AtlasInstaller ai = new AtlasInstaller(Collections.<String, String>emptyMap());

        Host child1 = new Host(Identity.root().createChild("ning", "0").createChild("child", "0"),
                               "base",
                               new My(),
                               asList(Uri.<Installer>valueOf("galaxy:rslv")));

        Host child2 = new Host(Identity.root().createChild("ning", "0").createChild("child", "1"),
                               "base",
                               new My(ImmutableMap.<String, Object>of("galaxy", "console")),
                               asList(Uri.<Installer>valueOf("galaxy:proc")));

        Bunch root = new Bunch(Identity.root()
                                       .createChild("ning", "0"), new My(), Arrays.<Element>asList(child1, child2));

        final Environment environment = new Environment();
        SystemMap map = new SystemMap(Arrays.<Element>asList(root));

        final Space space = InMemorySpace.newInstance();
        space.store(child1.getId(), new Server("10.0.0.1"));
        space.store(child2.getId(), new Server("10.0.0.2"));

        ObjectMapper mapper = ai.makeMapper(space, environment);
        String json = ai.generateSystemMap(mapper, map);
        System.out.println(json);
    }

    @Test
    public void testSerializationInAtlasInstallerWithAttributes() throws Exception
    {
        // the two props are required. Yea!
        AtlasInstaller ai = new AtlasInstaller(Collections.<String, String>emptyMap());

        Host child1 = new Host(Identity.root().createChild("ning", "0").createChild("child", "0"),
                               "base",
                               new My(),
                               asList(Uri.<Installer>valueOf("galaxy:rslv")));

        Host child2 = new Host(Identity.root().createChild("ning", "0").createChild("child", "1"),
                               "base",
                               new My(ImmutableMap.<String, Object>of("galaxy", "console")),
                               asList(Uri.<Installer>valueOf("galaxy:proc")));

        Bunch root = new Bunch(Identity.root()
                                       .createChild("ning", "0"), new My(), Arrays.<Element>asList(child1, child2));

        final Environment environment = new Environment();
        SystemMap map = new SystemMap(Arrays.<Element>asList(root));

        final Space space = InMemorySpace.newInstance();
        space.store(child1.getId(), new Server("10.0.0.1"));
        space.store(child2.getId(), new Server("10.0.0.2"));
        space.store(child1.getId(), "extra-atlas-attributes", "{ \"hello\":\"world\" }");
        ObjectMapper mapper = ai.makeMapper(space, environment);
        String json = ai.generateSystemMap(mapper, map);
        System.out.println(json);
    }

    @Test
    public void testOnEc2() throws Exception
    {
        assumeThat("ec2", isAvailable());

        Deployment deployment = EC2Helper.spinUpSingleInstance();
        Host node = Iterables.getOnlyElement(deployment.getSystemMap().findLeaves());

        AtlasInstaller ai = new AtlasInstaller(Collections.<String, String>emptyMap());

        Status node_info = ai.install(node, Uri.<Installer>valueOf("atlas"), deployment).get();
        System.out.println(node_info);

        ai.finish(deployment);

        EC2Helper.destroy(deployment);
    }
}
