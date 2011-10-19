package com.ning.atlas.aws;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.ec2.AmazonEC2AsyncClient;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
import com.amazonaws.services.ec2.model.TerminateInstancesResult;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import com.ning.atlas.NormalizedServerTemplate;
import com.ning.atlas.SystemMap;
import com.ning.atlas.base.Maybe;
import com.ning.atlas.logging.Logger;
import com.ning.atlas.space.Missing;
import com.ning.atlas.spi.BaseComponent;
import com.ning.atlas.spi.Identity;
import com.ning.atlas.spi.Provisioner;
import com.ning.atlas.spi.Server;
import com.ning.atlas.spi.Space;
import com.ning.atlas.spi.Uri;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static java.util.Arrays.asList;

public class EC2Provisioner extends BaseComponent implements Provisioner
{
    private final static Logger logger = Logger.get(EC2Provisioner.class);

    private final ExecutorService es = MoreExecutors.sameThreadExecutor();

    private final AmazonEC2Client ec2;
    private final String          keypairId;

    public EC2Provisioner(Map<String, String> attributes)
    {
        BasicAWSCredentials credentials = new BasicAWSCredentials(attributes.get("access_key"),
                                                                  attributes.get("secret_key"));
        keypairId = attributes.get("keypair_id");
        ec2 = new AmazonEC2AsyncClient(credentials);

    }

    public EC2Provisioner(AWSConfig config)
    {
        BasicAWSCredentials credentials = new BasicAWSCredentials(config.getAccessKey(), config.getSecretKey());
        ec2 = new AmazonEC2AsyncClient(credentials);
        keypairId = config.getKeyPairId();

    }

    @Override
    public Future<Server> provision(final NormalizedServerTemplate node,
                                    final Uri<Provisioner> uri,
                                    final Space space,
                                    final SystemMap map)
    {
        final Maybe<Server> s = space.get(node.getId(), Server.class, Missing.RequireAll);
        if (s.isKnown() && space.get(node.getId(), EC2InstanceInfo.class, Missing.RequireAll).isKnown()) {
            // we have an ec2 instance for this node already
            return Futures.immediateFuture(s.getValue());
        }
        else {
            // spin up an ec2 instance for this node

            return es.submit(new Callable<Server>()
            {
                @Override
                public Server call() throws Exception
                {
                    logger.info("Provisioning server for %s", node.getId());
                    final String ami_name = uri.getFragment();
                    RunInstancesRequest req = new RunInstancesRequest(ami_name, 1, 1);

                    req.setKeyName(keypairId);
                    RunInstancesResult rs = ec2.runInstances(req);

                    final Instance i = rs.getReservation().getInstances().get(0);

                    logger.debug("obtained ec2 instance {}", i.getInstanceId());

                    while (true) {
                        DescribeInstancesRequest dreq = new DescribeInstancesRequest();
                        dreq.setInstanceIds(Lists.newArrayList(i.getInstanceId()));
                        DescribeInstancesResult res = null;
                        try {
                            res = ec2.describeInstances(dreq);
                        }
                        catch (AmazonServiceException e) {
                            // sometimes amazon says the instance doesn't exist yet,
                            if (!e.getMessage().contains("does not exist")) {
                                throw new UnsupportedOperationException("Not Yet Implemented!", e);
                            }
                        }
                        if (res != null) {
                            Instance i2 = res.getReservations().get(0).getInstances().get(0);
                            if ("running".equals(i2.getState().getName())) {
                                logger.info("Obtained instance %s at %s for %s",
                                            i2.getInstanceId(), i2.getPublicDnsName(), node.getId());
                                Server server = new Server();
                                server.setExternalAddress(i2.getPublicIpAddress());
                                server.setInternalAddress(i2.getPublicIpAddress());

                                EC2InstanceInfo info = new EC2InstanceInfo();
                                info.setEc2InstanceId(i2.getInstanceId());
                                space.store(node.getId(), info);
                                space.store(node.getId(), server);
                                return server;
                            }
                            else {
                                try {
                                    Thread.sleep(1000);
                                }
                                catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new UnsupportedOperationException("Not Yet Implemented!", e);
                                }
                            }
                        }
                    }
                }
            });
        }
    }

    @Override
    public Future<String> describe(NormalizedServerTemplate server,
                                   Uri<Provisioner> uri,
                                   Space space,
                                   SystemMap map)
    {
        return Futures.immediateFuture("provision ec2 instance");
    }

    @Override
    protected void finishLocal(SystemMap map, Space space)
    {
        es.shutdown();
    }

    public void destroy(Identity id, Space space)
    {
        EC2InstanceInfo info = space.get(id, EC2InstanceInfo.class, Missing.RequireAll).getValue();
        ec2.terminateInstances(new TerminateInstancesRequest(asList(info.getEc2InstanceId())));
        logger.info("destroyed ec2 instance %s", info.getEc2InstanceId());
    }

    public static class EC2InstanceInfo
    {
        private String ec2InstanceId;

        public String getEc2InstanceId()
        {
            return ec2InstanceId;
        }

        public void setEc2InstanceId(String ec2InstanceId)
        {
            this.ec2InstanceId = ec2InstanceId;
        }
    }
}
