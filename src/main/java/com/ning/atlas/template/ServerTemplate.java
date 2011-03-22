package com.ning.atlas.template;

import com.google.common.collect.Lists;

import java.util.List;
import java.util.Map;

public class ServerTemplate extends DeployTemplate
{
    private String image;
    private final List<String> installations = Lists.newArrayList();

    public ServerTemplate(String name)
    {
        super(name);
    }

    public static ServerTemplate create(String name, Map<String, String> args)
    {
        ServerTemplate t = new ServerTemplate(name);

        t.setImage(args.get("image"));

        return t;
    }

    @Override
    public DeployTemplate addChild(DeployTemplate unit, int count)
    {
        throw new UnsupportedOperationException("May not add children to a server");
    }

    @Override
    public DeployTemplate shallowClone()
    {
        ServerTemplate t = new ServerTemplate(getName());
        t.setImage(getImage());
        t.addInstallations(getInstallations());
        for (String required_prop : getRequiredProperties()) {
            t.addRequiredProperty(required_prop);
        }

        return t;
    }

    @Override
    public DeployTemplate deepClone()
    {
        // no children on service, same as shallow clone
        return shallowClone();
    }

    @Override
    public UnitType getUnitType()
    {
        return UnitType.Service;
    }

    public List<String> getInstallations()
    {
        return installations;
    }

    public void setImage(String image)
    {
        this.image = image;
    }

    public String getImage()
    {
        return image;
    }

    public void addInstallations(List<String> installations)
    {
        this.installations.addAll(installations);
    }
}