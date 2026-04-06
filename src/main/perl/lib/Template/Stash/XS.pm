package Template::Stash::XS;

# PerlOnJava: XS Stash is not available. Fall back to the pure Perl
# Template::Stash which provides identical functionality.
#
# We override _assign to fix a bug in the pure-Perl Stash where
# DEFAULT on an array element does $root->{$item} (hash deref) instead
# of $root->[$item] (array deref) when checking existing values.
# In Perl 5 this code path is handled by the XS implementation.

use strict;
use warnings;
use Template::Stash;
use Scalar::Util qw(blessed);

our @ISA = ('Template::Stash');

# Fix: Template::Stash::_assign uses $root->{$item} on array refs
# when $default is true, causing "Not a HASH reference" errors.
# This override corrects the array branch to use $root->[$item].
sub _assign {
    my ($self, $root, $item, $args, $value, $default) = @_;
    my $rootref = ref $root;
    my $atroot  = ($root eq $self);
    $args    ||= [];
    $default ||= 0;

    return undef unless $root and defined $item;
    return undef if $Template::Stash::PRIVATE && $item =~ /$Template::Stash::PRIVATE/;

    if ($rootref eq 'HASH' || $atroot) {
        return ($root->{ $item } = $value)
            unless $default && $root->{ $item };
    }
    elsif ($rootref eq 'ARRAY' && $item =~ /^-?\d+$/) {
        return ($root->[$item] = $value)
            unless $default && $root->[$item];   # fixed: was $root->{$item}
    }
    elsif (blessed($root)) {
        return $root->$item(@$args, $value)
            unless $default && $root->$item();
    }
    else {
        die "don't know how to assign to [$root].[$item]\n";
    }

    return undef;
}

1;
