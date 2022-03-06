# About this branch / repo

This repo is a fork of NetBeans, but it is not supposed to work (or even compile) on its own.

The GraalVM Fiddle project uses some NetBeans classes and modules to provide code completions in the browser.
Some classes had to be modified and copied to GraalVM Fiddle to work in the new environment
(other modules are used unchanged as proper Maven dependencies and are not maintained here).
This repository is intended to make merging new NetBeans updates into the modified files easier.

This directory contains links to the `.java` files that have been copied.
The directory `/compiler-nbjavac/src/main/java/com/oracle/graalvm/fiddle/compiler/nbjavac/nb/`
in the GraalVM Fiddle repository contains exactly these `.java` files.

In addition to the links, this directory also contains some tools:
- `cpto` copies files from NB to Fiddle
- `cpfrom` copies files from Fiddle to NB

Both are able to locate the NB files from their own location (regardless of the current directory),
but they need to be passed the target (in `cpto`) or source (in `cpfrom`) on the commandline.

## Example usage

Assuming you are in this directory (your *current working directory* is `.../stolen`),
and the GraalVM Fiddle repo root path is stored in `$FIDDLE`.
All mentioned `git` commands refer to this repo, rather than Fiddle.

**To update NetBeans version in Fiddle:**

```sh
# merge the new NetBeans version, ignoring conflicts for now
git merge $netbeans_commit_or_tag_or_branch

# copy the files including the conflict marks into Fiddle
./cpto $FIDDLE/compiler-nbjavac/src/main/java/com/oracle/graalvm/fiddle/compiler/nbjavac/nb/

# under $FIDDLE/.../nb/, resolve conflicts so that Fiddle works
...

# copy the resolved files back here
./cpfrom $FIDDLE/compiler-nbjavac/src/main/java/com/oracle/graalvm/fiddle/compiler/nbjavac/nb/

# add and commit the changes here
git commit -a
```

**To further modify the adapted classes in Fiddle:**

```sh
# do your changes in Fiddle
...

# copy them here
./cpfrom $FIDDLE/compiler-nbjavac/src/main/java/com/oracle/graalvm/fiddle/compiler/nbjavac/nb/

# add and commit the changes here
git commit -a
```

**To add another file from NetBeans to Fiddle:**

(for example `/java/java.editor/src/org/netbeans/modules/editor/java/JavaCompletionItem.java`)

```sh
# link the new file - NOTE: due to `ln` semantics, the path must always be relative to /stolen/, not the current directory
ln -s ../java/java.editor/src/org/netbeans/modules/editor/java/JavaCompletionItem.java .

# make git aware of the new symlink
git add JavaCompletionItem.java

# copy the file into Fiddle
cp ../java/java.editor/src/org/netbeans/modules/editor/java/JavaCompletionItem.java $FIDDLE/compiler-nbjavac/src/main/java/com/oracle/graalvm/fiddle/compiler/nbjavac/nb/

# modify the class under $FIDDLE/.../nb/
...

# then continue as if any other stolen file was modified in Fiddle
./cpfrom $FIDDLE/compiler-nbjavac/src/main/java/com/oracle/graalvm/fiddle/compiler/nbjavac/nb/
git commit -a
```

**To stop using a NetBeans file in Fiddle:**

(for example `/java/java.editor/src/org/netbeans/modules/editor/java/JavaCompletionItem.java`)

```sh
# remove from Fiddle
rm $FIDDLE/compiler-nbjavac/src/main/java/com/oracle/graalvm/fiddle/compiler/nbjavac/nb/JavaCompletionItem.java

# remove the symlink here
git rm JavaCompletionItem.java

# restore the NetBeans version of the file to avoid future merge conflicts - the commit/tag/branch should be the latest revision of original NB merged into the current HEAD
git checkout $netbeans_commit_or_tag_or_branch ../java/java.editor/src/org/netbeans/modules/editor/java/JavaCompletionItem.java

# commit the changes here
git commit -a
```
