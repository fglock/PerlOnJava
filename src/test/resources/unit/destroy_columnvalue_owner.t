#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken refaddr);

# Minimal analogue of SQL::Statement::{Term,ColumnValue} + a wrapper holding
# the term (like SQL::Statement::Util::Column). Premature DESTROY on jperl
# cleared OWNER while the wrapper still referenced the term (CPAN SQL::Statement).

package TTerm;

sub new {
    my ( $class, $owner ) = @_;
    my $self = bless { OWNER => $owner }, $class;
    Scalar::Util::weaken( $self->{OWNER} );
    $self;
}

sub DESTROY {
    my $self = shift;
    undef $self->{OWNER};
}

package TColumn;

our @ISA = qw(TTerm);

sub new {
    my ( $class, $owner, $value ) = @_;
    my $self = $class->SUPER::new($owner);
    $self->{VALUE} = $value;
    $self;
}

package TWrap;

sub new {
    my ( $class, $term ) = @_;
    bless { term => $term }, $class;
}

sub term { $_[0]->{term} }

# SQL::Statement::Util::Column-style constructor (splat of 6 fields).
package TUtilCol;

sub new {
    my ( $class, $col_name, $table_name, $term, $display_name, $full_orig_name, $coldef ) = @_;
    bless { term => $term }, $class;
}

package main;

subtest 'wrapper keeps term alive; OWNER stays defined' => sub {
    my $owner = bless {}, 'TOwner';
    my $term  = TColumn->new( $owner, 'id' );
    my $wrap  = TWrap->new($term);
    ok( defined( $term->{OWNER} ), 'OWNER defined after construction' );
    ok( defined( $wrap->term->{OWNER} ), 'OWNER defined via wrapper' );
    is( refaddr( $wrap->term ), refaddr($term), 'same term object' );
};

subtest 'my %h = (term => $obj) then bless \\%h (Util::Column pattern)' => sub {
    my $owner = bless {}, 'TOwner';
    my $term  = TColumn->new( $owner, 'id' );

    package TUtilCol2;
    sub new {
        my ( $class, $term ) = @_;
        my %instance = ( term => $term, name => 'n' );
        bless \%instance, $class;
    }
    package main;

    my $u = TUtilCol2->new($term);
    ok( defined( $u->{term}{OWNER} ), 'OWNER still defined after hash+bless wrapper' );
};

subtest 'push row arrayref, return list, foreach like buildColumnObjects' => sub {
    my $owner = bless {}, 'TOwner';
    my $term  = TColumn->new( $owner, 'id' );
    my $expcol = [ 'id', undef, $term, 'id', 'id', {} ];
    my @columns;
    push @columns, $expcol;

    my @copy = @columns;
    foreach my $col (@copy) {
        my $u = TUtilCol->new( @{$col} );
        ok( defined( $u->{term}{OWNER} ), 'OWNER after push/return/foreach/new' );
    }
};

subtest 'list return + splat like buildColumnObjects' => sub {
    my $owner = bless {}, 'TOwner';
    my $term  = TColumn->new( $owner, 'id' );
    my $row   = [ 'id', undef, $term, 'id', 'id', {} ];
    my @cols  = ($row);

    my $u = TUtilCol->new( @{ $cols[0] } );
    ok( defined( $u->{term}{OWNER} ), 'util column new from splat' );
};

done_testing();
