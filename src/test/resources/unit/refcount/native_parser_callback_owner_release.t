use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);
use HTML::Parser;
our $calls = 0;
sub observe_event { ++$calls }
sub fail_event { die "event callback failed\n" }
{
    package NativeParserMethodOwner;
    our @ISA = qw(HTML::Parser);
    sub observe { ++$main::calls }
}
my $parser = HTML::Parser->new(api_version => 3);
$parser->handler(text => \&observe_event, 'self,text');
my $weak = $parser;
weaken($weak);
$parser->parse('text');
$parser->eof;
ok($calls > 0, 'native parser invokes the CODE callback');
undef $parser;
ok(!defined $weak, 'CODE callback argument arrays release their parser owner');
$calls = 0;
$parser = NativeParserMethodOwner->new(api_version => 3);
$parser->handler(text => 'observe', 'self,text');
$weak = $parser;
weaken($weak);
$parser->parse('text');
$parser->eof;
ok($calls > 0, 'native parser invokes the method callback');
undef $parser;
ok(!defined $weak, 'method callback argument arrays release their parser owner');
$parser = HTML::Parser->new(api_version => 3);
$parser->handler(start => \&fail_event, 'self,attr');
$weak = $parser;
weaken($weak);
eval { $parser->parse('<p>') };
like($@, qr/event callback failed/, 'native callback exception propagates');
undef $parser;
ok(!defined $weak, 'exception unwind releases callback argument owners');
my @events;
$parser = HTML::Parser->new(api_version => 3);
$parser->handler(text => \@events, 'self,text');
$weak = $parser;
weaken($weak);
$parser->parse('text');
$parser->eof;
ok(@events > 0, 'accumulator callback stores its event data');
undef $parser;
ok(defined $weak, 'accumulated self arguments remain genuine strong owners');
@events = ();
ok(!defined $weak, 'clearing accumulated events breaks the parser cycle');
done_testing();
