require 'json'

STDOUT.sync = true
kind = ARGV.fetch(0)
unless %w[numeric closure method string regex eval-string].include?(kind)
  abort "unknown workload: #{kind}"
end

captured = 17
closure = -> { captured + 1 }
object_class = Class.new do
  def initialize
    reset
  end

  def reset
    @x = 0
    @y = 0
  end

  def tick
    @x += 1
    @y += 1
    @x + @y
  end
end
object = object_class.new
pattern = /alpha-(\d{4})/
eval_id = 0

STDIN.each_line do |line|
  seconds = JSON.parse(line).fetch('seconds')
  started = Process.clock_gettime(Process::CLOCK_MONOTONIC)
  deadline = started + seconds
  operations = 0
  sink = 0
  x = 0
  object.reset if kind == 'method'

  while Process.clock_gettime(Process::CLOCK_MONOTONIC) < deadline
    case kind
    when 'numeric'
      1000.times { x += 1; sink += x }
    when 'closure'
      1000.times { sink += closure.call }
    when 'method'
      1000.times { sink += object.tick }
    when 'string'
      1000.times do
        value = 'alpha-1234-beta'
        sink += ('ALPHA' + value[6, 4].reverse).length
      end
    when 'regex'
      1000.times do
        match = pattern.match('alpha-1234-beta')
        sink += match[1].to_i if match
      end
    when 'eval-string'
      1000.times do
        eval_id += 1
        sink += eval("1 + #{eval_id} - #{eval_id}")
      end
    end
    operations += 1000
  end

  puts JSON.generate(
    operations: operations,
    checksum: sink.to_s,
    elapsed: Process.clock_gettime(Process::CLOCK_MONOTONIC) - started
  )
end
