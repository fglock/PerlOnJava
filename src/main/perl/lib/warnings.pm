package warnings;
our $VERSION = '1.74';

# Number of bytes in a warnings bit mask (required by caller.t tests)
# Matches Perl 5's WARNsize from warnings.h
our $BYTES = 21;

#
# Original warnings pragma is part of the Perl core, maintained by the Perl 5 Porters.
#
# PerlOnJava implementation by Flavio S. Glock.
# The XS implementation is in: src/main/java/org/perlonjava/perlmodule/Warnings.java
#

use XSLoader;
XSLoader::load( 'Warnings', $VERSION );

# Warning category offsets - used by experimental.pm to check if a warning exists
# These map warning category names to their bit positions
our %Offsets = (
    'all'				=> 0,
    'closure'				=> 2,
    'deprecated'			=> 4,
    'exiting'				=> 6,
    'glob'				=> 8,
    'io'				=> 10,
    'closed'				=> 12,
    'exec'				=> 14,
    'layer'				=> 16,
    'newline'				=> 18,
    'pipe'				=> 20,
    'unopened'				=> 22,
    'misc'				=> 24,
    'numeric'				=> 26,
    'once'				=> 28,
    'overflow'				=> 30,
    'pack'				=> 32,
    'portable'				=> 34,
    'recursion'				=> 36,
    'redefine'				=> 38,
    'regexp'				=> 40,
    'severe'				=> 42,
    'debugging'				=> 44,
    'inplace'				=> 46,
    'internal'				=> 48,
    'malloc'				=> 50,
    'signal'				=> 52,
    'substr'				=> 54,
    'syntax'				=> 56,
    'ambiguous'				=> 58,
    'bareword'				=> 60,
    'digit'				=> 62,
    'parenthesis'			=> 64,
    'precedence'			=> 66,
    'printf'				=> 68,
    'prototype'				=> 70,
    'qw'				=> 72,
    'reserved'				=> 74,
    'semicolon'				=> 76,
    'taint'				=> 78,
    'threads'				=> 80,
    'uninitialized'			=> 82,
    'unpack'				=> 84,
    'untie'				=> 86,
    'utf8'				=> 88,
    'void'				=> 90,
    'imprecision'			=> 92,
    'illegalproto'			=> 94,
    'deprecated::goto_construct'	=> 96,
    'deprecated::unicode_property_name'	=> 98,
    'non_unicode'			=> 100,
    'nonchar'				=> 102,
    'surrogate'				=> 104,
    'experimental'	=> 104,
    # Retired experimental categories share the parent bit so old source can
    # continue to query and suppress them after the features became stable.
    'experimental::alpha_assertions'	=> 104,
    'experimental::isa'	=> 104,
    # Compatibility alias used by code targeting Perl 5.20-5.24. Postfix
    # dereferencing is stable now, but old sources still disable its warning.
    'experimental::postderef'	=> 104,
    'experimental::regex_sets'	=> 106,
    'syscalls'	=> 108,
    'experimental::re_strict'	=> 110,
    'experimental::refaliasing'	=> 112,
    'locale'	=> 114,
    'missing'	=> 116,
    'redundant'	=> 118,
    'experimental::declared_refs'	=> 120,
    'deprecated::dot_in_inc'	=> 122,
    'shadow'	=> 124,
    'experimental::private_use'	=> 126,
    'experimental::uniprop_wildcards'	=> 128,
    'experimental::vlb'	=> 130,
    'experimental::try'	=> 132,
    'experimental::args_array_with_signatures'	=> 134,
    'experimental::builtin'	=> 136,
    'experimental::defer'	=> 138,
    'experimental::extra_paired_delimiters'	=> 140,
    'scalar'	=> 142,
    'deprecated::version_downgrade'	=> 144,
    'deprecated::delimiter_will_be_paired'	=> 146,
    'experimental::class'	=> 148,
    'deprecated::subsequent_use_version'	=> 150,
    'experimental::keyword_all'	=> 152,
    'experimental::keyword_any'	=> 154,
    'experimental::equ'			=> 162,
    'experimental::enhanced_xx'	=> 156,
    'experimental::signature_named_parameters'	=> 158,
    'missing_import'	=> 160,
);

# Warning category masks - public compatibility data used by modules such as
# Test::Warn. Offsets are bit positions, where the even bit enables a category
# and the following odd bit marks it fatal.
my %CategoryChildren = (
    'all' => [qw(
        closure deprecated exiting experimental glob imprecision io locale misc
        missing numeric once overflow pack portable recursion redefine redundant
        regexp scalar severe shadow signal substr syntax taint threads
        uninitialized unpack untie utf8 void
    )],
    'deprecated' => [qw(
        deprecated::goto_construct deprecated::unicode_property_name
        deprecated::dot_in_inc deprecated::version_downgrade
        deprecated::delimiter_will_be_paired
        deprecated::missing_import_called_with_args
        deprecated::subsequent_use_version
    )],
    'experimental' => [qw(
        experimental::regex_sets experimental::re_strict
        experimental::refaliasing experimental::declared_refs
        experimental::private_use experimental::uniprop_wildcards
        experimental::vlb experimental::try
        experimental::args_array_with_signatures experimental::builtin
        experimental::defer experimental::extra_paired_delimiters
        experimental::class experimental::keyword_all experimental::keyword_any
        experimental::alpha_assertions experimental::bitwise experimental::isa
        experimental::postderef experimental::equ
    )],
    'io'     => [qw(closed exec layer newline pipe unopened syscalls)],
    'severe' => [qw(debugging inplace internal malloc)],
    'syntax' => [qw(
        ambiguous bareword digit parenthesis precedence printf prototype qw
        reserved semicolon illegalproto
    )],
    'utf8'   => [qw(non_unicode nonchar surrogate)],
);

sub _mk_warning_mask {
    my ($fatal_bit, @categories) = @_;
    my $mask = "\0" x $BYTES;
    my %seen;
    while (@categories) {
        my $category = shift @categories;
        next if $seen{$category}++;
        next unless exists $Offsets{$category};
        vec($mask, $Offsets{$category} + $fatal_bit, 1) = 1;
        push @categories, @{ $CategoryChildren{$category} || [] };
    }
    return $mask;
}

our %Bits     = map { $_ => _mk_warning_mask(0, $_) } keys %Offsets;
our %DeadBits = map { $_ => _mk_warning_mask(1, $_) } keys %Offsets;

# NoOp warnings - warnings that have been removed but kept for compatibility
our %NoOp = map { $_ => 1 } qw(
    deprecated::goto_construct deprecated::missing_import_called_with_args
    deprecated::smartmatch experimental::alpha_assertions experimental::bitwise
    experimental::const_attr experimental::for_list experimental::isa
    experimental::lexical_subs experimental::postderef experimental::script_run
    experimental::signatures experimental::smartmatch
);

sub register_categories (;@) {
    # placeholder
}

1;
