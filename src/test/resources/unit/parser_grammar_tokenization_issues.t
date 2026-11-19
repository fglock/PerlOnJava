#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;
use File::Path qw(make_path);
use File::Temp qw(tempdir tempfile);

# #2113: `do` must execute the following statement in expression context.
sub parser_issue_2113_negate {
    local $_ = 'present';
    /^(present|equalityMatch|greaterOrEqual|lessOrEqual|approxMatch|substrings|extensibleMatch)/
        and do return ('not' => { $_[0], $_[1] });
    return;
}

my @negated = parser_issue_2113_negate('kind', 'value');
is_deeply(\@negated, ['not', { kind => 'value' }],
    '#2113: do return works as the right side of and');

# #2106: this is a reduced prefix of CPAN-Changes' two quoted preamble
# fields. The multiline hash and the following releases key reproduce the
# quote-state loss at the `=` in the URL query.
my $preamble_source = q{Revision history for Perl extension Business::PayPal::API.\n\nRelease 0.71\n-------------------------------------------\n\n- Merge in changes from Ron Phipps.\n  - Reduce SOAP version requirement.\n  - Protection eligibility support.\n- Merge in changes from Tomas Doran.\n  - CPAN packaging enhancements.\n- Extract out more payer details from XML. (PayerName, NameSuffix, PayerCountry).\n- Fix https://rt.cpan.org/Public/Bug/Display.html?id=67386\n\nRelease 0.70 - First OALDERS release\n-------------------------------------\n- Fixes RT #70424 (Syntax deprecated in perl v5.14)\n>>>>>>> Fixes RT #70424 and bumps version to 0.70 for release.};
my ($fixture_fh, $fixture_path) = tempfile();
print {$fixture_fh} "{\n",
    "  \"preamble\" => \"$preamble_source\",\n",
    "  \"raw_preamble\" => \"$preamble_source\",\n",
    "  \"releases\" => []\n",
    "};\n";
close $fixture_fh or die "close $fixture_path: $!";
my $loaded_fixture = do $fixture_path;
is(ref($loaded_fixture), 'HASH',
    '#2106: do FILE preserves the top-level hash with quoted URL values');
unlink $fixture_path or die "unlink $fixture_path: $!";

# #2105: keyword autoquoting in an unparenthesized call takes precedence over
# interpreting `if` as a statement modifier.
sub parser_issue_2105_name {
    return $_[0] eq 'if' && ref($_[1]) eq 'CODE';
}
my $keyword_call = parser_issue_2105_name if => sub { 1 };
ok($keyword_call, '#2105: if => sub is a fat-comma call argument');

# #2099: Sub::Uplevel installs CORE::GLOBAL::caller with an optional-argument
# prototype. Loading this small test fixture reproduces the parser context
# without requiring the external Test::Exception distribution.
my $module_root = tempdir(CLEANUP => 1);
make_path("$module_root/Sub", "$module_root/Mo");
open my $uplevel_fh, '>', "$module_root/Sub/Uplevel.pm"
    or die "open Sub::Uplevel fixture: $!";
print {$uplevel_fh} <<'UPLEVEL';
package Sub::Uplevel;
sub _normal_caller (;$) { CORE::caller($_[0] || 0) }
*CORE::GLOBAL::caller = \&_normal_caller;
1;
UPLEVEL
close $uplevel_fh or die "close Sub::Uplevel fixture: $!";

open my $mo_fh, '>', "$module_root/Mo/is.pm"
    or die "open Mo::is fixture: $!";
print {$mo_fh} <<'MOIS';
package Mo::is;
$Mo::is::VERSION = '0.40';
$M = 'Mo::';
*{$M.'is::e'}=sub{my($P,$e,$o)=@_;$o->{is}=sub{my($m,$n,%a)=@_;$a{is}or return$m;sub{$#_&&$a{is}eq'ro'&&caller ne'Mo::coerce'?die$n.' is ro':$m->(@_)}}};
1;
MOIS
close $mo_fh or die "close Mo::is fixture: $!";

{
    local @INC = ($module_root, @INC);
    delete $INC{'Sub/Uplevel.pm'};
    delete $INC{'Mo/is.pm'};
    my $loaded = eval { require Sub::Uplevel; require Mo::is; 1 };
    diag("#2099 parser diagnostic: $@") if !$loaded;
    ok($loaded, "#2099: adjacent quote after ne parses after caller override load");
}

# #2090: unary minus binds to the numeric subject before =~ / !~.
is((-1 =~ /^[0-9]+$/) ? 1 : 0, 0,
    '#2090: negative literal does not match a nonnegative digit pattern');
is((-1 !~ /^[0-9]+$/) ? 1 : 0, 1,
    '#2090: negative literal matches the negated pattern');

done_testing;
