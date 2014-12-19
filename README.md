# version-check-service

This library allows you to perform version checks with dujour. To use this in your project,
add the following to your `project.clj` file:

```
[puppetlabs/dujour-version-check "0.1.0"]

```

Then, call the `version-check` function. This function takes two arguments,
`product-name` and `update-server-url`. `update-server-url` should be a string
containing the URL of the update server. `product-name` can either be a string
containing the artifact-id or a map with the following schema:

```clj
{:group-id schema/Str
 :artifact-id schema/Str}
```

If only the artifact id is provided, the group id will default to
`"puppetlabs.packages"`.

## License

Copyright © 2014 Puppet Labs

Distributed under the [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.html)
