# version-check-service

[![Build Status](https://travis-ci.org/puppetlabs/dujour-version-check.png?branch=master)](https://travis-ci.org/puppetlabs/dujour-version-check)

This library allows you to perform version checks with dujour. To use this in your project,
add the following to your `project.clj` file:

```
[puppetlabs/dujour-version-check "0.1.2"]

```

Then, call the `check-for-updates!` function. This function takes two arguments,
`request-values` and `update-server-url`. `update-server-url` should be a string
containing the URL of the update server. `request-values` is a map that currently only
supports a single key, `:product-name`. The value contained at this key can either be a string
containing the artifact-id or a map with the following schema:

```clj
{:group-id schema/Str
 :artifact-id schema/Str}
```

If only the artifact id is provided, the group id will default to
`"puppetlabs.packages"`.

The request map can also accept a `:certname` string, which can be used
to uniquely identify a user. This value will be SHA-512 hashed before
being sent to the server.

This library provides one other public API function, `get-version-string`. This function
takes one argument, `product-name`, which should be the artifact id as a string. It
optionally takes one more argument, `group-id`, which should be the group-id of the
desired artifact as a string.

## License

Copyright © 2014 Puppet Labs

Distributed under the [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.html)

Support
-------

Please log tickets and issues at our [JIRA
tracker](http://tickets.puppetlabs.com).  A [mailing
list](https://groups.google.com/forum/?fromgroups#!forum/puppet-users) is
available for asking questions and getting help from others. In addition there
is an active #puppet channel on Freenode.

We use semantic version numbers for our releases, and recommend that users stay
as up-to-date as possible by upgrading to patch releases and minor releases as
they become available.

Bugfixes and ongoing development will occur in minor releases for the current
major version. Security fixes will be backported to a previous major version on
a best-effort basis, until the previous major version is no longer maintained.

Long-term support, including security patches and bug fixes, is available for
commercial customers. Please see the following page for more details:

[Puppet Enterprise Support
Lifecycle](http://puppetlabs.com/misc/puppet-enterprise-lifecycle)
