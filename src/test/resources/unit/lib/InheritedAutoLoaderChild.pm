package InheritedAutoLoaderChild;

use strict;
use warnings;
use InheritedAutoLoaderParent;

our @ISA = ('InheritedAutoLoaderParent');
our $AUTOLOAD;

sub AUTOLOAD {
    return if $AUTOLOAD =~ /::DESTROY\z/;
    die "child AUTOLOAD incorrectly selected for $AUTOLOAD";
}

1;
