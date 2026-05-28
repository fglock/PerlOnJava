package Package::MoreUtil;

use strict;
use warnings;

our $VERSION = '0.592';

sub _stash_name {
    my ($pkg) = @_;
    $pkg =~ s/\A::/main::/;
    $pkg =~ s/::\z//;
    return "${pkg}::";
}

sub package_exists {
    my ($pkg) = @_;
    no strict 'refs';
    my $stash = \%{ _stash_name($pkg) };
    return scalar(keys %$stash) ? 1 : 0;
}

sub list_subpackages {
    my ($pkg) = @_;
    no strict 'refs';
    my $base = _stash_name($pkg);
    (my $base_pkg = $base) =~ s/::\z//;
    my @res;
    for my $key (keys %{ $base }) {
        next unless $key =~ /::\z/;
        (my $name = $key) =~ s/::\z//;
        push @res, "$base_pkg\::$name";
    }
    return sort @res;
}

sub list_package_contents {
    my ($pkg) = @_;
    no strict 'refs';
    return %{ _stash_name($pkg) };
}

1;
