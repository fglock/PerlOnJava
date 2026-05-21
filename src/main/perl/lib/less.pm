package less;

use strict;
use warnings;

our $VERSION = '0.03';

sub _pack_tags {
    return join ' ', @_;
}

sub _unpack_tags {
    return grep { defined and length }
        map { split ' ' }
        grep { defined } @_;
}

sub stash_name {
    return $_[0];
}

sub of {
    my $class = shift;

    return unless defined wantarray;

    my $hinthash = (caller 0)[10] || {};
    my %tags;
    @tags{ _unpack_tags($hinthash->{ $class->stash_name }) } = ();

    if (@_) {
        exists $tags{$_} and return 1 for @_;
        return;
    }

    return keys %tags;
}

sub import {
    my $class = shift;
    my $stash = $class->stash_name;

    @_ = 'please' if !@_;

    my %tags;
    @tags{ _unpack_tags(@_, $^H{$stash}) } = ();
    $^H{$stash} = _pack_tags(keys %tags);

    return;
}

sub unimport {
    my $class = shift;
    my $stash = $class->stash_name;

    if (@_) {
        my %tags;
        @tags{ _unpack_tags($^H{$stash}) } = ();
        delete @tags{ _unpack_tags(@_) };

        my $new = _pack_tags(keys %tags);
        if (length $new) {
            $^H{$stash} = $new;
        }
        else {
            delete $^H{$stash};
        }
    }
    else {
        delete $^H{$stash};
    }

    return;
}

1;
