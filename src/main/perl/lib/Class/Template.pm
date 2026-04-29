package Class::Template;

# PerlOnJava bundled shim for Class::Template.
#
# The historical Class::Template distribution (Dean Roehrich's
# Class-Eroot-19960603 tarball) ships without a Makefile.PL, so cpan
# can't install it. Class::Visitor (and a few other old modules)
# `use Class::Template` for its `struct`/`members` code generator,
# which means `jcpan -t Class::Visitor` fails at test time with
# "Can't locate Class/Template.pm in @INC".
#
# This is a clean-room re-implementation of the documented API:
#
#   struct( NAME => { field => 'TYPE', ... } )   # hash-backed struct
#   struct( NAME => [ field => 'TYPE', ... ] )   # array-backed struct
#   members  PKG    { field => 'TYPE', ... } ;   # add members to PKG
#
# TYPE is one of:
#   '$'    scalar slot (default)
#   '@'    arrayref slot, accessor takes optional index
#   '%'    hashref  slot, accessor takes optional key
#   'CLS'  nested struct of class CLS, initialised via &CLS::new()
#   '*X'   like X above but accessor returns a reference to the slot
#
# Class::Visitor also calls Class::Template::parse_fields directly to
# re-derive the (methods, refs, arrays, hashes) bookkeeping, so that
# helper is exposed too.

use strict;
require Exporter;

our @ISA    = qw(Exporter);
our @EXPORT = qw(struct members);
our $VERSION = '1.10';

# If non-zero, struct()/members() print the generated code instead of
# eval'ing it. Matches the historical Class::Template::print toggle.
our $print = 0;

sub struct {
    my ($pkg, $ref) = @_;
    _emit($pkg, $ref, 0);
}

sub members {
    my ($pkg, $ref) = @_;
    _emit($pkg, $ref, 1);
}

sub _emit {
    my ($pkg, $ref, $is_member) = @_;

    my @methods;
    my (%refs, %arrays, %hashes);

    my $ctor_name = $is_member ? 'InitMembers' : 'new';
    my $body = "{\n  package $pkg;\n  sub $ctor_name {\n";
    parse_fields($ref, \$body, \@methods, \%refs, \%arrays, \%hashes, $is_member);
    $body .= "      bless \$r;\n  }\n";
    build_methods($ref, \$body, \@methods, \%refs, \%arrays, \%hashes);
    $body .= "}\n1;\n";

    if ($print) {
        print $body;
        return;
    }
    my $ok = eval $body;
    die $@ if $@;
    return $ok;
}

sub parse_fields {
    my ($ref, $out, $methods, $refs, $arrays, $hashes, $is_member) = @_;
    my $type = ref $ref;

    if ($type eq 'HASH') {
        $$out .= $is_member
            ? "      my (\$r) = \@_ ? shift : {};\n"
            : "      my (\$r) = {};\n";
        for my $name (keys %$ref) {
            my $val = $ref->{$name};
            if (defined $val and $val =~ /^\*(.)/) {
                $refs->{$name}++;
                $val = $1;
            }
            if    ($val eq '@') { $$out .= "      \$r->{'$name'} = [];\n"; $arrays->{$name}++ }
            elsif ($val eq '%') { $$out .= "      \$r->{'$name'} = {};\n"; $hashes->{$name}++ }
            elsif ($val ne '$') { $$out .= "      \$r->{'$name'} = \&${val}::new();\n" }
            else                { $$out .= "      \$r->{'$name'} = undef;\n" }
            push @$methods, $name;
        }
    }
    elsif ($type eq 'ARRAY') {
        $$out .= $is_member
            ? "      my (\$r) = \@_ ? shift : [];\n"
            : "      my (\$r) = [];\n";
        my $idx = 0;
        my $slot = 0;
        while ($idx < @$ref) {
            my $name = $ref->[$idx];
            my $val  = $ref->[$idx + 1];
            push @$methods, $name;
            if (defined $val and $val =~ /^\*(.)/) {
                $refs->{$name}++;
                $val = $1;
            }
            if    ($val eq '@') { $$out .= "      \$r->[$slot] = []; # $name\n"; $arrays->{$name}++ }
            elsif ($val eq '%') { $$out .= "      \$r->[$slot] = {}; # $name\n"; $hashes->{$name}++ }
            elsif ($val ne '$') { $$out .= "      \$r->[$slot] = \&${val}::new();\n" }
            else                { $$out .= "      \$r->[$slot] = undef; # $name\n" }
            $slot++;
            $idx  += 2;
        }
    }
}

sub build_methods {
    my ($ref, $out, $methods, $refs, $arrays, $hashes) = @_;
    my $type = ref $ref;
    my $slot = 0;

    for my $name (@$methods) {
        my ($pre, $pst, $cmt) = ('', '', '');
        if ($refs->{$name}) {
            $pre = "\\(";
            $pst = ")";
            $cmt = " # returns ref";
        }

        $$out .= "  sub $name {$cmt\n      my \$r = shift;\n";

        my $elem;
        if ($type eq 'ARRAY') {
            $elem = "[$slot]";
            $slot++;
        }
        else {
            $elem = "{'$name'}";
        }

        my $idx = '';
        if ($arrays->{$name}) {
            $$out .= "      my \$i;\n";
            $$out .= "      \@_ ? (\$i = shift) : return \$r->$elem;\n";
            $idx = "->[\$i]";
        }
        elsif ($hashes->{$name}) {
            $$out .= "      my \$i;\n";
            $$out .= "      \@_ ? (\$i = shift) : return \$r->$elem;\n";
            $idx = "->{\$i}";
        }
        $$out .= "      \@_ ? (\$r->$elem$idx = shift) : $pre\$r->$elem$idx$pst;\n";
        $$out .= "  }\n";
    }
}

1;

__END__

=head1 NAME

Class::Template - struct/member accessor generator (PerlOnJava shim)

=head1 DESCRIPTION

Pure-Perl reimplementation of the Class::Template API used by old CPAN
modules such as L<Class::Visitor>. Provided by PerlOnJava because the
original distribution lacks a Makefile.PL and therefore cannot be
installed via cpan.

=head1 SEE ALSO

L<Class::Struct> in core Perl provides a similar (and more modern) API.

=cut
