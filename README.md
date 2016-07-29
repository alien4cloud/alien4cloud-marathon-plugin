# Alien4cloud-marathon-plugin

A plugin to integrate Marathon as an orchestrator for Alien4cloud. It features deployment of complex Docker containers topologies on a Mesos/Marathon cluster using MarathonLB and MesosDNS for service discovery.

**Disclaimer:** This project is at an alpha stage and still undergoing development. It is not yet fit for production. Any feedback would be highly appreciated !

## Getting started

This readme assumes that you are already familiar with Alien4Cloud. If you aren't yet, please check out our [website](http://alien4cloud.github.io).

### Deploy a marathon cluster

The first thing you'll want to do is setting up a Marathon+Mesos cluster on the Cloud provider of your choosing. Luckily, Alien4Cloud can do all the heavy-lifting for you.
- First, upload the following CSARs into Alien:
 - [docker-engine](https://github.com/alien4cloud/samples/tree/master/docker-engine)
 - [mesos-types](https://github.com/alien4cloud/mesos-tosca-blueprints)
 - [docker-types](https://github.com/alien4cloud/docker-tosca-types)
- Then, create your own custom Mesos TOSCA composition or use a convenient template [we made for you](https://github.com/alien4cloud/mesos-tosca-blueprints/blob/master/alien-templates/marathon-template.yml).
**Note** that if your IaaS doesn't automatically assigns *public ips* you'll have to add a *public network* to your template.
- Finally, setup the Cloudify orchestrator on the Cloud provider and hit deploy.

If you're more of a Youtube person, you can also follow our demonstrxation [video](https://youtu.be/IoOzf7wwCnM).

### Install the Marathon orchestrator

- Download the plugin from our Maven repository [here](https://fastconnect.org/maven/content/repositories/opensource/alien4cloud/alien4cloud-marathon-plugin/1.3.0-SM2/alien4cloud-marathon-plugin-1.3.0-SM2.zip), or clone this repository and build it yourself using `mvn clean install`.
- Upload the orchestrator archive **alien4cloud-marathon-plugin-${VERSION}.zip** into alien using the plugin view.
- Set up the orchestrator by simply giving Marathon's URL.
Create an empty location - you don't need to create any resources for now.

### Try out the Nodecellar demo

You can now define and deploy your own custom TOSCA docker node types using the [docker-types](https://github.com/alien4cloud/docker-tosca-types) or try our Nodecellar demo app.

- Upload Nodecellar [nodes-types](https://github.com/alien4cloud/docker-tosca-types/blob/master/examples/nodecellar_types_sample.yml), which consists of a Docker container with MongoDB and a another Docker container with Node.js and the Nodecellar app, into Alien.
- Create your own application topology or start from our [template](https://github.com/alien4cloud/docker-tosca-types/examples/nodecellar_template.yml).
- Hit deploy and enjoy the fun !

We also made a Youtube video for this. It's [here](https://www.youtube.com/watch?v=kXrNanNMkhU).

## Known limitations and caveats

This plugin is still work-in-progress. We decided to release it to the world early in hope to get traction and feedback from the community. Please make use of the Issues section !

- Currently, we only support running Docker containers in Marathon.
- The plugin **will not** operate if you omit to deploy MesosDNS and MarathonLB (with the haproxy_group *internal*) on your cluster. If you're new to Mesos+Marathon, consider using our [template]((https://github.com/alien4cloud/mesos-tosca-blueprints/blob/master/alien-templates/marathon-template.yml)).
- Persistent volumes are still WIP in Marathon. We plan to implement them in the near future.
- It is not yet possible to scale a Docker container in Alien. This should be addressed soon.
- We did not exactly follow the TOSCA model for Docker containers as it is still incubating. More on this [here](https://github.com/alien4cloud/docker-tosca-types).
- It not possible to stop the deployment of an application. Wait for it to be deployed then hit un-deploy.
- The connexion to Marathon is NOT secured.
- Health checks events are not parsed. However, the health of each instance is polled when refreshing the runtime view.
