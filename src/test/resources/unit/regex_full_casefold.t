#!/usr/bin/env perl
use strict;
use warnings;
use utf8;
use feature 'fc';
use Test::More tests => 20;

ok("ss" =~ /^\x{00DF}$/iu, 'sharp s pattern matches its full fold');
ok("\x{00DF}" =~ /^ss$/iu, 'full fold pattern matches sharp s');
ok("\x{017F}\x{017F}" =~ /^\x{00DF}$/iu,
    'simple-fold components participate in a full fold');
ok("st" =~ /^[\x{FB06}]$/iu, 'full fold works from a character class');
ok("\x{FB06}" =~ /^st$/iu, 'ligature matches the reverse full fold');
ok("\x{FB03}" =~ /^ffi$/iu, 'three-character full fold is expanded');
ok("\x{00DF}" =~ /^[s][s]$/iu,
    'reverse full fold crosses adjacent character classes');
ok(":\x{00DF}:" =~ /:[s][s]:/iu,
    'reverse full fold crosses classes beside literal delimiters');
ok(":\x{00DF}:" =~ /:s[s]:/iu,
    'reverse full fold crosses a literal followed by a class');
ok(":\x{00DF}:" =~ /:[s]s:/iu,
    'reverse full fold crosses a class followed by a literal');
ok(":\x{00DF}:" !~ /:s[x]:/iu,
    'mixed literal and class folds retain each required component');
ok(":\x{0390}:" =~ /:[\x{03B9}]\x{0308}[\x{0301}]:/iu,
    'three-codepoint full fold crosses literal and class atoms');
ok(":\x{1FD3}:" =~ /:[\x{03B9}]\x{0308}[\x{0301}]:/iu,
    'reverse full fold includes every source for a shared Greek sequence');
ok(":\x{FB03}:" =~ /:[f]f[i]:/iu,
    'three-codepoint ligature fold crosses alternating literal and class atoms');
my $eval_subject = ":\x{00DF}:";
my $eval_fold = q[$eval_subject =~ /:[s][s]:/iu];
ok(eval $eval_fold, 'reverse full fold survives evaluated regex source');
ok("\x{00DF}" !~ /^[s]$/iu,
    'reverse full fold does not make one class consume two characters');

ok("\x{00DF}" =~ /^ss$/ia, '/a permits Unicode-to-ASCII case folds');
ok("\x{00DF}" !~ /^ss$/iaa, '/aa forbids Unicode-to-ASCII case folds');

my @fc_warnings;
{
    no warnings;
    use warnings 'uninitialized';
    local $SIG{__WARN__} = sub { push @fc_warnings, shift };
    fc(undef);
    {
        package Local::FcUndef;
        use overload q{""} => sub { undef }, fallback => 1;
    }
    fc(bless {}, 'Local::FcUndef');
}
is(scalar @fc_warnings, 2, 'fc warns for undef and overloaded undef values');
like(join('', @fc_warnings), qr/Use of uninitialized value.*in fc/s,
    'fc warnings use the uninitialized category message');
