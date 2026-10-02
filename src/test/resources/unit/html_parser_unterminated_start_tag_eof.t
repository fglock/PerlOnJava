use strict;
use warnings;
use Test::More;

unless (eval { require HTML::Parser; 1 }) {
    plan skip_all => 'HTML::Parser is unavailable under this Perl';
}

my $path = 'html_parser_unterminated_start_tag_eof.tmp';
open my $fh, '>', $path or die "create $path: $!";
print {$fh} "<foo a=b\n";
close $fh or die "close $path: $!";

my @events;
my $parser = HTML::Parser->new(api_version => 3);
$parser->handler(
    default => sub { push @events, [@_] },
    'event,text,tagname,attr',
);
my $eof_result = eval { $parser->parse_file($path); 1 };
unlink $path;

ok($eof_result, 'parse_file reaches EOF for an unterminated start tag');
is($events[1][0], 'comment', 'the incomplete start tag is emitted as a comment');
like($events[1][1], qr/<foo a=b/, 'the comment event preserves the incomplete markup');

done_testing;
