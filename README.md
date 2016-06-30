# alien4cloud-plugin-marathon

A plugin to integrate Marathon as an orchestrator for Alien4cloud.
Based upon the Sample plugin with rest service and ui components.

## How-to

First, you need to setup a working Marathon cluster with Mesos-DNS and Marathon-lb up and running.

### Deploy a marathon cluster

First, upload Mesos-types and the Marathon template into alien. Make sure to use the correct [branch](https://fastconnect.org/gitlab/fraissea/mesos-tosca-blueprints/tree/marathon-service-discovery).
- To deploy on AWS, you're all set. Just create an application in Alien based upon the Marathon template and hit deploy.
- To deploy on Openstack, just add a public network to the Marathon topology and connect both slaves and masters nodes to it. Then hit deploy.

 
### Set up mesos-dns and marathon-lb

Automatic setup of the service discovery components is not yet implemented.
- Choose a slave amongst your cluster to be your DNS resolver. Note down its ip.
- Use this ip to update the file resources/templates.discovery/mesos-dns-template.json
- Launch mesos-dns by running : `curl -H 'Content-Type: Application/json' http://MARATHON_IP:8080/v2/apps -d@mesos-dns-template.json`
- Update the file resources/templates.discovery/marathon-lb-template.json with marathon's IP.
- Launch marathon-lb by running : `curl -H 'Content-Type: Application/json' http://MARATHON_IP:8080/v2/apps -d@marathon-lb-template.json`
- update the dns resolvers for every slave using `ssh -tt ubuntu@${SLAVE_IP} "sudo sed -i '1s/^/nameserver ${DNS_IP}\n /' /etc/resolvconf/resolv.conf.d/head; sudo resolvconf -u"`
 
That's it. The service discovery system should be up and running.

### Upload docker types

Upload docker-types and the nodecellar types into your Alien. Repo is [here](https://fastconnect.org/gitlab/fraissea/docker-tosca-types).

### Install the Marathon orchestrator

Checkout the [repository](https://fastconnect.org/gitlab/alien4cloud/a4c-marathon-plugin). Use the master branch.
Build the orchestrator using `mvn clean install`, then upload it to alien using the plugin view.
Set up the orchestrator by simpling giving Marathon's URL.
Create an empty location.

### Launch nodecellar

Create a new application. Add to the canvas the mongodb and nodecellar node types, then connect them. Now, just go ahead and deploy !