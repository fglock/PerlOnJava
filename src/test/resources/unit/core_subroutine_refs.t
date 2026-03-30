use strict;
use warnings;
use Test::More tests => 29;

# ==============================================
# Tests for \&CORE::X subroutine references
# ==============================================

# --- Tier 1: Simple unary operators (prototype "_") ---

my $length = \&CORE::length;
is(ref($length), 'CODE', '\&CORE::length returns CODE ref');
is($length->("hello"), 5, 'CORE::length via ref works');

my $abs = \&CORE::abs;
is($abs->(-42), 42, 'CORE::abs via ref works');

my $uc = \&CORE::uc;
is($uc->("hello"), "HELLO", 'CORE::uc via ref works');

my $lc = \&CORE::lc;
is($lc->("HELLO"), "hello", 'CORE::lc via ref works');

my $hex = \&CORE::hex;
is($hex->("ff"), 255, 'CORE::hex via ref works');

my $chr = \&CORE::chr;
is($chr->(65), "A", 'CORE::chr via ref works');

my $ord = \&CORE::ord;
is($ord->("A"), 65, 'CORE::ord via ref works');

# --- Prototype check ---

is(prototype(\&CORE::length), '_', 'prototype of \&CORE::length is "_"');
is(prototype(\&CORE::abs), '_', 'prototype of \&CORE::abs is "_"');
is(prototype(\&CORE::push), '\@@', 'prototype of \&CORE::push is "\@@"');
is(prototype(\&CORE::atan2), '$$', 'prototype of \&CORE::atan2 is "$$"');

# --- Tier 1: Binary operators (prototype "$$") ---

my $atan2 = \&CORE::atan2;
ok(abs($atan2->(1, 1) - 0.785398163397448) < 0.0001, 'CORE::atan2 via ref works');

# --- Tier 1: Zero-arg operators (prototype "") ---

my $time = \&CORE::time;
ok($time->() > 0, 'CORE::time via ref returns positive value');

# --- Tier 1: Optional scalar (prototype ";$") ---

my $rand = \&CORE::rand;
my $r = $rand->(100);
ok($r >= 0 && $r < 100, 'CORE::rand via ref works');

# --- Tier 1: Array operators (push, pop, shift, unshift) ---

my $push = \&CORE::push;
my @arr;
$push->(\@arr, 1, 2, 3);
is_deeply(\@arr, [1, 2, 3], 'CORE::push via ref works');

my $pop = \&CORE::pop;
my $popped = $pop->(\@arr);
is($popped, 3, 'CORE::pop via ref works');
is_deeply(\@arr, [1, 2], 'array after pop');

my $shift_ref = \&CORE::shift;
my $shifted = $shift_ref->(\@arr);
is($shifted, 1, 'CORE::shift via ref works');
is_deeply(\@arr, [2], 'array after shift');

my $unshift = \&CORE::unshift;
$unshift->(\@arr, 10, 20);
is_deeply(\@arr, [10, 20, 2], 'CORE::unshift via ref works');

# --- Aliasing through glob assignment ---

BEGIN { *my_length = \&CORE::length; }
is(my_length("world"), 5, 'aliased CORE::length via glob works');

BEGIN { *my_push = \&CORE::push; }
my @arr2;
my_push @arr2, 4, 5, 6;
is_deeply(\@arr2, [4, 5, 6], 'aliased CORE::push via glob works');

# --- Tier 2: Bareword-only operators ---

my $chomp = \&CORE::chomp;
is(ref($chomp), 'CODE', '\&CORE::chomp returns CODE ref');
eval { $chomp->("test"); };
like($@, qr/cannot be called directly/, 'CORE::chomp via ref dies correctly');

my $chop = \&CORE::chop;
is(ref($chop), 'CODE', '\&CORE::chop returns CODE ref');
eval { $chop->("test"); };
like($@, qr/cannot be called directly/, 'CORE::chop via ref dies correctly');

# --- Tier 3: Keywords (no subroutine form) ---
# defined(\&CORE::print) should return false in standard Perl,
# but the ref still exists as CODE type

# --- defined() check ---

ok(defined(\&CORE::length), 'defined(\&CORE::length) is true');
ok(defined(\&CORE::chomp), 'defined(\&CORE::chomp) is true');
