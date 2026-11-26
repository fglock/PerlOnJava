use strict;
use warnings;
use Test::More;

my (@warnings, $result, $error);
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    $result = eval <<'PERL';
package FileScopeLexicalSignatureCalls;
use v5.20;
use warnings;
use experimental qw(lexical_subs signatures);

my sub call ($name, $args, $expect) { return $name }
my sub multi {
    @_ > 2
        ? call($_[0] => [ $_[1] ] => $_[2])
        : call($_[0] => [ ] => $_[1])
}
sub all_text ($check) { call all_text => [ ] => $check }
sub attr { multi attr => @_ }

package main;
FileScopeLexicalSignatureCalls::all_text('value') . ':'
    . FileScopeLexicalSignatureCalls::attr('value');
PERL
    $error = $@;
}

is($error, '', 'named subs can call signatured file-scope lexical subs with bareword arguments');
is($result, 'all_text:attr', 'both direct and nested lexical calls retain their bindings');
is_deeply(\@warnings, [], 'file-scope lexical sub calls do not warn about sharing');

done_testing();
