#!/usr/bin/env perl
#
# storable_gen_fixtures.pl
# Regenerates the binary Storable fixtures used by the parallel-agent
# Phase-1 reader work. Run with system perl (NOT jperl) — the whole
# point is to capture upstream's output.
#
#   perl dev/tools/storable_gen_fixtures.pl
#
# Output: src/test/resources/storable_fixtures/<name>.bin
#         src/test/resources/storable_fixtures/<name>.expect
#
# The .expect file is a Data::Dumper string with $Sortkeys = 1
# and $Useqq = 1 — agents' JUnit tests compare against these.
#
# Coverage groups (one fixture per opcode-or-shape):
#   scalars/*    - SX_UNDEF, SV_UNDEF/YES/NO, BYTE, INTEGER, NETINT,
#                  DOUBLE, SCALAR, LSCALAR, UTF8STR, LUTF8STR
#   refs/*       - SX_REF, OVERLOAD, OBJECT (backref), WEAKREF
#   containers/* - SX_ARRAY, SX_HASH, SX_FLAG_HASH, SX_SVUNDEF_ELEM
#   blessed/*    - SX_BLESS, SX_IX_BLESS (multi-class fixture forces ix)
#   hooks/*      - SX_HOOK with a tiny inline class
#   misc/*       - SX_REGEXP, refusal cases (CODE, TIED) get .expect
#                  files that say "<die: msg>"
#
# Both nstore (network order) and store (native order) variants are
# emitted for the scalar group so agents exercise both endianness
# paths.

use strict;
use warnings;
use Storable qw(store nstore freeze nfreeze);
use Scalar::Util qw(weaken);
use Data::Dumper;
use File::Path qw(make_path);
use FindBin;

my $OUT = "$FindBin::Bin/../../src/test/resources/storable_fixtures";
$OUT = "src/test/resources/storable_fixtures" unless -d (-e "$FindBin::Bin/../../src/test/resources" ? "$FindBin::Bin/../../src/test/resources" : "");
make_path($OUT) unless -d $OUT;

local $Data::Dumper::Sortkeys = 1;
local $Data::Dumper::Useqq    = 1;
local $Data::Dumper::Indent   = 1;
local $Data::Dumper::Terse    = 1;

sub emit {
    my ($name, $data, %opt) = @_;
    my $base = "$OUT/$name";
    my $dir  = $base; $dir =~ s{/[^/]+$}{};
    make_path($dir) unless -d $dir;
    my $netorder = !$opt{native};
    if ($netorder) {
        nstore $data, "$base.bin";
    } else {
        store $data, "$base.bin";
    }
    open my $fh, '>', "$base.expect" or die "$base.expect: $!";
    print {$fh} Data::Dumper::Dumper($data);
    close $fh;
    printf "  %-40s  %5d bytes  %s\n", $name, -s "$base.bin", $netorder ? "(net)" : "(native)";
}

sub emit_die {
    my ($name, $message) = @_;
    my $base = "$OUT/$name";
    my $dir  = $base; $dir =~ s{/[^/]+$}{};
    make_path($dir) unless -d $dir;
    open my $fh, '>', "$base.expect.die" or die;
    print {$fh} $message, "\n";
    close $fh;
    printf "  %-40s  (refusal: %s)\n", $name, $message;
}

print "Writing Storable fixtures to $OUT\n";

# --- scalars (network order) ----------------------------------------
emit 'scalars/undef'        => \(my $u = undef);
emit 'scalars/byte_pos'     => \42;       # SX_BYTE  -- value in [-128,127]
emit 'scalars/byte_neg'     => \-7;
emit 'scalars/byte_zero'    => \0;
emit 'scalars/integer_big'  => \1_000_000_000;        # SX_INTEGER (or NETINT under nstore)
emit 'scalars/integer_neg'  => \-2_000_000_000;
emit 'scalars/integer_long' => \1_000_000_000_000;    # 64-bit
emit 'scalars/double_pi'    => \3.14159265358979;
emit 'scalars/double_neg'   => \-2.5e10;
emit 'scalars/scalar_short' => \"hello world";        # SX_SCALAR (1-byte len)
emit 'scalars/scalar_long'  => \("x" x 1000);         # SX_LSCALAR (4-byte len)
emit 'scalars/utf8_short'   => \"caf\x{e9}";          # SX_UTF8STR
emit 'scalars/utf8_long'    => \("\x{2603}" x 200);   # SX_LUTF8STR
emit 'scalars/empty'        => \"";
emit 'scalars/sv_yes'       => \!!1;                  # may emit SV_YES or BOOLEAN_TRUE
emit 'scalars/sv_no'        => \!1;

# --- scalars (native order) for endianness coverage -----------------
emit 'scalars_native/integer_big'  => \1_000_000_000, native => 1;
emit 'scalars_native/integer_long' => \1_000_000_000_000, native => 1;
emit 'scalars_native/double_pi'    => \3.14159265358979, native => 1;
emit 'scalars_native/scalar_long'  => \("x" x 1000), native => 1;

# --- refs -----------------------------------------------------------
my $shared = { name => "shared" };
emit 'refs/scalar_ref'     => \\42;
emit 'refs/ref_to_array'   => \[1, 2, 3];
emit 'refs/ref_to_hash'    => \{ a => 1, b => 2 };
emit 'refs/cycle'          => do {
    my %h; $h{self} = \%h; \%h;
};
emit 'refs/shared_struct'  => [ $shared, $shared, $shared ];   # SX_OBJECT backrefs
my $weak = { x => 1 };
my $weak_holder = $weak;
weaken $weak_holder;
emit 'refs/weakref'        => \$weak_holder;

# --- containers -----------------------------------------------------
emit 'containers/array_empty' => [];
emit 'containers/array_mixed' => [ 1, "two", 3.0, undef, [4, 5] ];
emit 'containers/hash_empty'  => {};
emit 'containers/hash_mixed'  => { int => 1, str => "x", deep => { a => [1, 2] } };
# UTF-8 keys force SX_FLAG_HASH on modern perls
emit 'containers/hash_utf8_keys' => { "\x{e9}cole" => 1, "caf\x{e9}" => 2 };

# --- blessed --------------------------------------------------------
{
    package Foo::Bar; sub new { bless { v => $_[1] }, $_[0] }
}
emit 'blessed/single'      => Foo::Bar->new(42);
emit 'blessed/two_classes' => [ Foo::Bar->new(1), bless({}, 'Other::Class'), Foo::Bar->new(2) ];

# --- hooks ----------------------------------------------------------
{
    package Hookey;
    sub new { bless { v => $_[1] }, $_[0] }
    sub STORABLE_freeze {
        my ($self, $cloning) = @_;
        return ("frozen-cookie:" . $self->{v}, []);
    }
    sub STORABLE_thaw {
        my ($self, $cloning, $cookie) = @_;
        my ($v) = $cookie =~ /^frozen-cookie:(.*)$/;
        $self->{v} = $v;
    }
}
emit 'hooks/simple_hook' => Hookey->new("xyzzy");

# --- misc / refusals -----------------------------------------------
emit 'misc/regexp' => qr/^foo.*bar$/i;
# CODE and TIED would die at retrieve under our policy; document that
# with .expect.die files so JUnit can assert the message.
emit_die 'misc/coderef'    => "Can't retrieve code references";
emit_die 'misc/tied_hash'  => "Storable: tied hash retrieval not supported";

print "done.\n";
