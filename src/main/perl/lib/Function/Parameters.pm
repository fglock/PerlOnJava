package Function::Parameters;

use strict;
use warnings;

our $VERSION = '0.01';

require Function::Parameters::Info;

# PerlOnJava installs syntax through the lexical parser extension. This
# runtime hook validates direct imports while leaving registration to parser.
sub import {
    shift if @_ && $_[0] eq __PACKAGE__;
    for my $argument (@_) {
        if (ref($argument) eq 'HASH') {
            for my $keyword (keys %$argument) {
                _validate_keyword($keyword);
                my $definition = $argument->{$keyword};
                my $defaults = ref($definition) eq 'HASH'
                    ? ($definition->{defaults} // 'function') : $definition;
                _validate_type($defaults);
            }
        } elsif (!ref($argument)) {
            next if $argument =~ /\A:(?:std|modifiers|strict|lax)\z/;
            next if $argument =~ /\A(?:fun|method)\z/;
            die "Function::Parameters: $argument is not exported\n";
        } else {
            die "Function::Parameters: import argument must be a tag, keyword, or hash\n";
        }
    }
    return;
}

sub _validate_keyword {
    my ($keyword) = @_;
    die "Function::Parameters: '$keyword' is not a valid identifier for a keyword\n"
        unless $keyword =~ /\A[A-Za-z_]\w*\z/;
}

sub _validate_type {
    my ($type) = @_;
    $type = "$type";
    $type =~ s/_(?:strict|lax)\z//;
    die "Function::Parameters: '$type' is not a valid type\n"
        unless $type =~ /\A(?:function|method|classmethod|before|after|around|augment|override)\z/;
}

sub unimport { return }

1;
